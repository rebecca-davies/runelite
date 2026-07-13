package net.runelite.client.plugins.tamagotchi;

import com.google.inject.Provides;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.inject.Inject;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Tamagotchi",
	description = "Play Tamagotchi P1 in an overlay",
	tags = {"tamagotchi", "pet", "game", "virtual"},
	enabledByDefault = false
)
@Slf4j
public class TamagotchiPlugin extends Plugin
{
	private static final int LCD_WIDTH = 32;
	private static final int LCD_HEIGHT = 16;
	private static final int ICON_NUM = 8;
	private static final long TS_FREQ = 1000;
	private static final File SAVE_FILE = new File(RuneLite.RUNELITE_DIR, "tamagotchi.sav");

	@Inject
	private TamagotchiConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private TamagotchiOverlay overlay;

	private TamaCpu cpu;
	private int[] romProgram;
	private Thread gameThread;
	private volatile boolean running;
	@Getter
	private volatile boolean loaded;

	// Display double-buffer
	private final boolean[][] matrixBack = new boolean[LCD_HEIGHT][LCD_WIDTH];
	private volatile boolean[][] matrixFront = new boolean[LCD_HEIGHT][LCD_WIDTH];
	private final boolean[] iconsBack = new boolean[ICON_NUM];
	private volatile boolean[] iconsFront = new boolean[ICON_NUM];

	// Sound
	private SourceDataLine audioLine;
	private volatile float currentFreqHz;
	private volatile boolean soundPlaying;
	private Thread soundThread;

	// Auto-save
	private Thread shutdownHook;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		keyManager.registerKeyListener(overlay);
		mouseManager.registerMouseListener(overlay);
		shutdownHook = new Thread(this::saveState, "TamagotchiShutdownSave");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
		tryLoadRom();
	}

	@Override
	protected void shutDown()
	{
		// Stop game thread first so CPU state is stable for saving
		running = false;
		if (gameThread != null)
		{
			gameThread.interrupt();
			try
			{
				gameThread.join(2000);
			}
			catch (InterruptedException ignored)
			{
			}
			gameThread = null;
		}
		// Now safe to save — game thread is stopped, cpu still exists
		saveState();
		// Clean up
		cpu = null;
		romProgram = null;
		loaded = false;
		stopSound();
		overlayManager.remove(overlay);
		keyManager.unregisterKeyListener(overlay);
		mouseManager.unregisterMouseListener(overlay);
		try
		{
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
		}
		catch (IllegalStateException ignored)
		{
			// JVM already shutting down
		}
	}

	@Provides
	TamagotchiConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TamagotchiConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("tamagotchi".equals(event.getGroup()) && "romPath".equals(event.getKey()))
		{
			saveState();
			stopGame();
			tryLoadRom();
		}
	}

	boolean isRunning()
	{
		return running && loaded;
	}

	boolean[][] getMatrix()
	{
		return matrixFront;
	}

	boolean[] getIcons()
	{
		return iconsFront;
	}

	void pressButton(int btn, boolean pressed)
	{
		if (cpu != null)
		{
			cpu.setButton(btn, pressed);
		}
	}

	private void tryLoadRom()
	{
		String romPath = config.romPath();
		if (romPath == null || romPath.isEmpty())
		{
			return;
		}

		Path path = Paths.get(romPath);
		if (!Files.exists(path))
		{
			log.warn("Tamagotchi ROM not found: {}", romPath);
			return;
		}

		try
		{
			byte[] data = Files.readAllBytes(path);
			romProgram = new int[data.length / 2];
			for (int i = 0; i < romProgram.length; i++)
			{
				int hi = data[i * 2] & 0xFF;
				int lo = data[i * 2 + 1] & 0xFF;
				romProgram[i] = ((hi << 8) | lo) & 0xFFF;
			}

			cpu = new TamaCpu();
			cpu.init(romProgram, new HalImpl(), TS_FREQ);

			// Try to restore saved state
			loadSavedState();

			loaded = true;
			startGame();
			startSound();
		}
		catch (IOException e)
		{
			log.error("Failed to load Tamagotchi ROM", e);
		}
	}

	private void startGame()
	{
		running = true;
		gameThread = new Thread(this::gameLoop, "Tamagotchi");
		gameThread.setDaemon(true);
		gameThread.start();
	}

	private void stopGame()
	{
		running = false;
		if (gameThread != null)
		{
			gameThread.interrupt();
			try
			{
				gameThread.join(2000);
			}
			catch (InterruptedException ignored)
			{
			}
			gameThread = null;
		}
		saveState();
		cpu = null;
		romProgram = null;
		loaded = false;
	}

	private void gameLoop()
	{
		// Offset baseNanos so targetTicks matches the restored tickCounter at startup
		long baseNanos = System.nanoTime()
			- cpu.getTickCounter() * 1_000_000_000L / TamaCpu.TICK_FREQUENCY;
		long lastScreenSwap = System.nanoTime();
		long lastAutoSave = System.nanoTime();

		while (running)
		{
			long now = System.nanoTime();
			long elapsedNanos = now - baseNanos;
			long targetTicks = elapsedNanos * TamaCpu.TICK_FREQUENCY / 1_000_000_000L;
			long currentTicks = cpu.getTickCounter();

			if (currentTicks < targetTicks)
			{
				int batch = (int) Math.min((targetTicks - currentTicks) / 4 + 1, 5000);
				for (int i = 0; i < batch && running; i++)
				{
					cpu.step();
				}
			}
			else
			{
				Thread.yield();
			}

			// Screen update at ~30fps
			if (now - lastScreenSwap >= 33_000_000L)
			{
				lastScreenSwap = now;
				swapBuffers();
			}

			// Auto-save every 30 seconds
			if (now - lastAutoSave >= 30_000_000_000L)
			{
				lastAutoSave = now;
				saveState();
			}
		}
	}

	private void swapBuffers()
	{
		boolean[][] newMatrix = new boolean[LCD_HEIGHT][LCD_WIDTH];
		for (int r = 0; r < LCD_HEIGHT; r++)
		{
			System.arraycopy(matrixBack[r], 0, newMatrix[r], 0, LCD_WIDTH);
		}
		matrixFront = newMatrix;

		boolean[] newIcons = new boolean[ICON_NUM];
		System.arraycopy(iconsBack, 0, newIcons, 0, ICON_NUM);
		iconsFront = newIcons;
	}

	// --- Save/Load ---

	private void saveState()
	{
		if (cpu == null)
		{
			return;
		}
		try
		{
			int[] state = cpu.saveState();
			try (DataOutputStream out = new DataOutputStream(new FileOutputStream(SAVE_FILE)))
			{
				out.writeInt(state.length);
				for (int v : state)
				{
					out.writeInt(v);
				}
			}
			log.info("Tamagotchi state saved");
		}
		catch (IOException e)
		{
			log.error("Failed to save Tamagotchi state", e);
		}
	}

	private void loadSavedState()
	{
		if (!SAVE_FILE.exists() || cpu == null)
		{
			return;
		}
		try
		{
			int[] state;
			try (DataInputStream in = new DataInputStream(new FileInputStream(SAVE_FILE)))
			{
				int len = in.readInt();
				state = new int[len];
				for (int i = 0; i < len; i++)
				{
					state[i] = in.readInt();
				}
			}
			cpu.loadState(state);
			cpu.refreshHw();
			log.info("Tamagotchi state restored");
		}
		catch (IOException e)
		{
			log.warn("Could not restore Tamagotchi state", e);
		}
	}

	// --- Sound ---

	private void startSound()
	{
		soundThread = new Thread(this::soundLoop, "TamagotchiSound");
		soundThread.setDaemon(true);
		soundThread.start();
	}

	private void stopSound()
	{
		soundPlaying = false;
		currentFreqHz = 0;
		if (soundThread != null)
		{
			soundThread.interrupt();
			soundThread = null;
		}
		if (audioLine != null)
		{
			audioLine.stop();
			audioLine.close();
			audioLine = null;
		}
	}

	private void soundLoop()
	{
		try
		{
			int sampleRate = 44100;
			AudioFormat fmt = new AudioFormat(sampleRate, 8, 1, true, false);
			audioLine = AudioSystem.getSourceDataLine(fmt);
			audioLine.open(fmt, 2048);
			audioLine.start();

			byte[] buf = new byte[256];
			double phase = 0;
			// Narrow pulse (15% duty cycle) sounds brighter/thinner like a real piezo buzzer.
			// A 50% square wave has strong odd harmonics that make it sound bassy on PC speakers.
			double dutyCycle = 0.15;
			byte amplitude = 12; // quiet — the real tama is a tiny piezo, not a speaker

			while (running)
			{
				if (!soundPlaying || currentFreqHz <= 0 || config.muted())
				{
					java.util.Arrays.fill(buf, (byte) 0);
					audioLine.write(buf, 0, buf.length);
					phase = 0;
					continue;
				}

				double phaseInc = currentFreqHz / sampleRate;
				for (int i = 0; i < buf.length; i++)
				{
					buf[i] = (byte) (phase < dutyCycle ? amplitude : -amplitude);
					phase += phaseInc;
					if (phase >= 1.0)
					{
						phase -= 1.0;
					}
				}
				audioLine.write(buf, 0, buf.length);
			}
		}
		catch (Exception e)
		{
			if (running)
			{
				log.warn("Tamagotchi sound error", e);
			}
		}
	}

	// --- HAL ---

	private class HalImpl implements TamaCpu.Hal
	{
		@Override
		public long getTimestamp()
		{
			return System.currentTimeMillis();
		}

		@Override
		public void sleepUntil(long ts)
		{
		}

		@Override
		public void setLcdMatrix(int x, int y, boolean val)
		{
			if (y >= 0 && y < LCD_HEIGHT && x >= 0 && x < LCD_WIDTH)
			{
				matrixBack[y][x] = val;
			}
		}

		@Override
		public void setLcdIcon(int icon, boolean val)
		{
			if (icon >= 0 && icon < ICON_NUM)
			{
				iconsBack[icon] = val;
			}
		}

		@Override
		public void setFrequency(int freqDeciHz)
		{
			currentFreqHz = freqDeciHz / 10.0f;
		}

		@Override
		public void playFrequency(boolean enable)
		{
			soundPlaying = enable;
		}

		@Override
		public void updateScreen()
		{
			swapBuffers();
		}
	}
}
