package net.runelite.client.plugins.tamagotchi;

import java.util.Arrays;

/**
 * Faithful Java port of a Tamagotchi P1 hardware emulator (E0C6S46/S48 CPU).
 * Ported from TamaLIB's cpu.c, hw.c, and tamalib.c.
 */
public class TamaCpu
{
	public interface Hal
	{
		long getTimestamp();
		void setLcdMatrix(int x, int y, boolean val);
		void setLcdIcon(int icon, boolean val);
		void setFrequency(int freqDeciHz);
		void playFrequency(boolean enable);
		void updateScreen();
		void sleepUntil(long deadline);
	}

	// Memory constants
	public static final int MEMORY_SIZE = 4096;
	public static final int TICK_FREQUENCY = 32768;
	public static final int OSC1_FREQUENCY = 32768;
	public static final int OSC3_FREQUENCY = 1000000;

	// Memory map (E0C6S48)
	public static final int MEM_RAM_ADDR = 0x000;
	public static final int MEM_RAM_SIZE = 0x300;
	public static final int MEM_DISPLAY1_ADDR = 0xE00;
	public static final int MEM_DISPLAY1_SIZE = 0x066;
	public static final int MEM_DISPLAY2_ADDR = 0xE80;
	public static final int MEM_DISPLAY2_SIZE = 0x066;
	public static final int MEM_IO_ADDR = 0xF00;
	public static final int MEM_IO_SIZE = 0x080;

	// LCD
	public static final int LCD_WIDTH = 32;
	public static final int LCD_HEIGHT = 16;
	public static final int ICON_NUM = 8;

	// Timer periods (in ticks)
	private static final long TIMER_2HZ = 16384;
	private static final long TIMER_4HZ = 8192;
	private static final long TIMER_8HZ = 4096;
	private static final long TIMER_16HZ = 2048;
	private static final long TIMER_32HZ = 1024;
	private static final long TIMER_64HZ = 512;
	private static final long TIMER_128HZ = 256;
	private static final long TIMER_256HZ = 128;

	// Flags
	private static final int FLAG_C = 0x1;
	private static final int FLAG_Z = 0x2;
	private static final int FLAG_D = 0x4;
	private static final int FLAG_I = 0x8;

	// Interrupt indices
	private static final int INT_PROG_TIMER = 0;
	private static final int INT_SERIAL = 1;
	private static final int INT_K10_K13 = 2;
	private static final int INT_K00_K03 = 3;
	private static final int INT_STOPWATCH = 4;
	private static final int INT_CLOCK_TIMER = 5;

	// Button constants
	public static final int BTN_LEFT = 0;
	public static final int BTN_MIDDLE = 1;
	public static final int BTN_RIGHT = 2;

	// Pin constants
	private static final int PIN_K00 = 0;
	private static final int PIN_K01 = 1;
	private static final int PIN_K02 = 2;
	private static final int PIN_K03 = 3;
	private static final int PIN_K10 = 4;
	private static final int PIN_K11 = 5;
	private static final int PIN_K12 = 6;
	private static final int PIN_K13 = 7;

	// LCD segment position mapping
	static final int[] SEG_POS = {
		0, 1, 2, 3, 4, 5, 6, 7, 32, 8, 9, 10, 11, 12, 13, 14, 15,
		33, 34, 35, 31, 30, 29, 28, 27, 26, 25, 24, 36, 23, 22, 21,
		20, 19, 18, 17, 16, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,
		47, 48, 49, 50
	};

	// Buzzer frequency table (in deci-Hz)
	private static final int[] BUZZER_FREQS = {40960, 32768, 27307, 23406, 20480, 16384, 13653, 11703};

	static class Op
	{
		final int code, mask, shiftArg0, maskArg0, cycles, index;

		Op(int code, int mask, int shiftArg0, int maskArg0, int cycles, int index)
		{
			this.code = code;
			this.mask = mask;
			this.shiftArg0 = shiftArg0;
			this.maskArg0 = maskArg0;
			this.cycles = cycles;
			this.index = index;
		}
	}

	static class Interrupt
	{
		int factorFlagReg;
		int maskReg;
		boolean triggered;
		int vector;

		Interrupt(int factorFlagReg, int maskReg, int vector)
		{
			this.factorFlagReg = factorFlagReg;
			this.maskReg = maskReg;
			this.vector = vector;
			this.triggered = false;
		}
	}

	// CPU registers
	private int pc;
	private int nextPc;
	private int x, y;
	private int a, b;
	private int np;
	private int sp;
	private int flags;

	// Memory
	private int[] memory = new int[MEMORY_SIZE];
	private int[] program;

	// HAL
	private Hal hal;

	// Interrupts
	private Interrupt[] interrupts = new Interrupt[6];

	// Input pins
	private int[] inputStates = new int[2];

	// Timing
	private long tickCounter;
	private long clkTimer2hzTs;
	private long clkTimer4hzTs;
	private long clkTimer8hzTs;
	private long clkTimer16hzTs;
	private long clkTimer32hzTs;
	private long clkTimer64hzTs;
	private long clkTimer128hzTs;
	private long clkTimer256hzTs;
	private long progTimerTs;
	private boolean progTimerEnabled;
	private int progTimerData;
	private int progTimerRld;

	// CPU state
	private int callDepth;
	private boolean cpuHalted;
	private int cpuFrequency = OSC1_FREQUENCY;
	private long scaledCycleAccumulator;
	private long refTs;
	private long tsFreq;
	private int speedRatio = 0; // 0 = max speed, pacing handled externally
	private int previousCycles;

	// Op table
	private Op[] ops;

	public TamaCpu()
	{
		buildOpTable();
		initInterrupts();
	}

	private void initInterrupts()
	{
		// Priority order: PROG_TIMER(0), SERIAL(1), K10_K13(2), K00_K03(3), STOPWATCH(4), CLOCK_TIMER(5)
		// factorFlagReg and maskReg are I/O register addresses
		// Mapping from C code: 0xF00=CLK, 0xF01=SW, 0xF02=PROG, 0xF03=SERIAL, 0xF04=K00, 0xF05=K10
		interrupts[INT_PROG_TIMER] = new Interrupt(0xF02, 0xF12, 0x0C);
		interrupts[INT_SERIAL] = new Interrupt(0xF03, 0xF13, 0x0A);
		interrupts[INT_K10_K13] = new Interrupt(0xF05, 0xF15, 0x08);
		interrupts[INT_K00_K03] = new Interrupt(0xF04, 0xF14, 0x06);
		interrupts[INT_STOPWATCH] = new Interrupt(0xF01, 0xF11, 0x04);
		interrupts[INT_CLOCK_TIMER] = new Interrupt(0xF00, 0xF10, 0x02);
	}

	private void buildOpTable()
	{
		// The Op.index field references the switch case in executeOp().
		// Array ordering matters: first match wins in the linear search.
		// More specific masks (0xFFF) must come before less specific (0xFF0, 0xFC0)
		// when their opcode ranges overlap.
		int n = 0;
		ops = new Op[108];

		// --- PSET: 0xE40, mask 0xFE0 ---
		ops[n++] = new Op(0xE40, 0xFE0, 0, 0, 5, 0);        // PSET

		// --- Branch/Jump/Call (0xF00 mask group) ---
		// More specific 0xFFF entries first, then 0xF00 entries
		ops[n++] = new Op(0xFE8, 0xFFF, 0, 0, 5, 6);        // JPBA
		ops[n++] = new Op(0xFDF, 0xFFF, 0, 0, 7, 9);        // RET
		ops[n++] = new Op(0xFDE, 0xFFF, 0, 0, 12, 10);      // RETS
		ops[n++] = new Op(0xFFB, 0xFFF, 0, 0, 5, 12);       // NOP5
		ops[n++] = new Op(0xFFF, 0xFFF, 0, 0, 7, 13);       // NOP7
		ops[n++] = new Op(0xFF8, 0xFFF, 0, 0, 5, 14);       // HALT
		ops[n++] = new Op(0x000, 0xF00, 0, 0, 5, 1);        // JP
		ops[n++] = new Op(0x100, 0xF00, 0, 0, 12, 11);      // RETD
		ops[n++] = new Op(0x200, 0xF00, 0, 0, 5, 2);        // JP_C
		ops[n++] = new Op(0x300, 0xF00, 0, 0, 5, 3);        // JP_NC
		ops[n++] = new Op(0x400, 0xF00, 0, 0, 7, 7);        // CALL
		ops[n++] = new Op(0x500, 0xF00, 0, 0, 7, 8);        // CALZ
		ops[n++] = new Op(0x600, 0xF00, 0, 0, 5, 4);        // JP_Z
		ops[n++] = new Op(0x700, 0xF00, 0, 0, 5, 5);        // JP_NZ
		ops[n++] = new Op(0x800, 0xF00, 0, 0, 5, 18);       // LD_Y
		ops[n++] = new Op(0x900, 0xF00, 0, 0, 5, 49);       // LBPX
		ops[n++] = new Op(0xB00, 0xF00, 0, 0, 5, 17);       // LD_X

		// --- 0xA** group: 0xFF0 mask ---
		ops[n++] = new Op(0xA00, 0xFF0, 0, 0, 7, 31);       // ADC_XH
		ops[n++] = new Op(0xA10, 0xFF0, 0, 0, 7, 32);       // ADC_XL
		ops[n++] = new Op(0xA20, 0xFF0, 0, 0, 7, 33);       // ADC_YH
		ops[n++] = new Op(0xA30, 0xFF0, 0, 0, 7, 34);       // ADC_YL
		ops[n++] = new Op(0xA40, 0xFF0, 0, 0, 7, 35);       // CP_XH
		ops[n++] = new Op(0xA50, 0xFF0, 0, 0, 7, 36);       // CP_XL
		ops[n++] = new Op(0xA60, 0xFF0, 0, 0, 7, 37);       // CP_YH
		ops[n++] = new Op(0xA70, 0xFF0, 0, 0, 7, 38);       // CP_YL
		ops[n++] = new Op(0xA80, 0xFF0, 2, 0x00C, 7, 83);   // ADD_R_Q
		ops[n++] = new Op(0xA90, 0xFF0, 2, 0x00C, 7, 85);   // ADC_R_Q
		ops[n++] = new Op(0xAA0, 0xFF0, 2, 0x00C, 7, 86);   // SUB
		ops[n++] = new Op(0xAB0, 0xFF0, 2, 0x00C, 7, 88);   // SBC_R_Q
		ops[n++] = new Op(0xAC0, 0xFF0, 2, 0x00C, 7, 90);   // AND_R_Q
		ops[n++] = new Op(0xAD0, 0xFF0, 2, 0x00C, 7, 92);   // OR_R_Q
		ops[n++] = new Op(0xAE0, 0xFF0, 2, 0x00C, 7, 94);   // XOR_R_Q
		ops[n++] = new Op(0xAF0, 0xFF0, 0, 0, 7, 99);       // RLC

		// --- 0xC** and 0xD** group: 0xFC0 mask ---
		// NOT (0xFCF mask) must come before XOR_R_I (0xFC0 mask) since they overlap
		ops[n++] = new Op(0xC00, 0xFC0, 4, 0x030, 7, 82);   // ADD_R_I
		ops[n++] = new Op(0xC40, 0xFC0, 4, 0x030, 7, 84);   // ADC_R_I
		ops[n++] = new Op(0xC80, 0xFC0, 4, 0x030, 7, 89);   // AND_R_I
		ops[n++] = new Op(0xCC0, 0xFC0, 4, 0x030, 7, 91);   // OR_R_I
		ops[n++] = new Op(0xD0F, 0xFCF, 4, 0, 7, 107);      // NOT (before XOR_R_I)
		ops[n++] = new Op(0xD00, 0xFC0, 4, 0x030, 7, 93);   // XOR_R_I
		ops[n++] = new Op(0xD40, 0xFC0, 4, 0x030, 7, 87);   // SBC_R_I
		ops[n++] = new Op(0xD80, 0xFC0, 4, 0x030, 7, 97);   // FAN_R_I
		ops[n++] = new Op(0xDC0, 0xFC0, 4, 0x030, 7, 95);   // CP_R_I

		// --- 0xE** group ---
		ops[n++] = new Op(0xE00, 0xFC0, 4, 0x030, 5, 39);   // LD_R_I
		ops[n++] = new Op(0xE60, 0xFF0, 0, 0, 5, 45);       // LDPX_MX
		ops[n++] = new Op(0xE70, 0xFF0, 0, 0, 5, 47);       // LDPY_MY
		ops[n++] = new Op(0xE80, 0xFFC, 0, 0, 5, 19);       // LD_XP_R
		ops[n++] = new Op(0xE84, 0xFFC, 0, 0, 5, 20);       // LD_XH_R
		ops[n++] = new Op(0xE88, 0xFFC, 0, 0, 5, 21);       // LD_XL_R
		ops[n++] = new Op(0xE8C, 0xFFC, 0, 0, 5, 100);      // RRC
		ops[n++] = new Op(0xE90, 0xFFC, 0, 0, 5, 22);       // LD_YP_R
		ops[n++] = new Op(0xE94, 0xFFC, 0, 0, 5, 23);       // LD_YH_R
		ops[n++] = new Op(0xE98, 0xFFC, 0, 0, 5, 24);       // LD_YL_R
		ops[n++] = new Op(0xEA0, 0xFFC, 0, 0, 5, 25);       // LD_R_XP
		ops[n++] = new Op(0xEA4, 0xFFC, 0, 0, 5, 26);       // LD_R_XH
		ops[n++] = new Op(0xEA8, 0xFFC, 0, 0, 5, 27);       // LD_R_XL
		ops[n++] = new Op(0xEB0, 0xFFC, 0, 0, 5, 28);       // LD_R_YP
		ops[n++] = new Op(0xEB4, 0xFFC, 0, 0, 5, 29);       // LD_R_YH
		ops[n++] = new Op(0xEB8, 0xFFC, 0, 0, 5, 30);       // LD_R_YL
		ops[n++] = new Op(0xEC0, 0xFF0, 2, 0x00C, 5, 40);   // LD_R_Q
		// INC_X (0xEE0, 0xFFF) must come before LDPX_R (0xEE0, 0xFF0)
		ops[n++] = new Op(0xEE0, 0xFFF, 0, 0, 5, 15);       // INC_X
		ops[n++] = new Op(0xEE0, 0xFF0, 2, 0x00C, 5, 46);   // LDPX_R
		// INC_Y (0xEF0, 0xFFF) must come before LDPY_R (0xEF0, 0xFF0)
		ops[n++] = new Op(0xEF0, 0xFFF, 0, 0, 5, 16);       // INC_Y
		ops[n++] = new Op(0xEF0, 0xFF0, 2, 0x00C, 5, 48);   // LDPY_R

		// --- 0xF** group ---
		// Specific 0xFFF entries before 0xFF0/0xFFC entries
		ops[n++] = new Op(0xF00, 0xFF0, 2, 0x00C, 7, 96);   // CP_R_Q
		ops[n++] = new Op(0xF10, 0xFF0, 2, 0x00C, 7, 98);   // FAN_R_Q
		ops[n++] = new Op(0xF28, 0xFFC, 0, 0, 7, 103);      // ACPX
		ops[n++] = new Op(0xF2C, 0xFFC, 0, 0, 7, 104);      // ACPY
		ops[n++] = new Op(0xF38, 0xFFC, 0, 0, 7, 105);      // SCPX
		ops[n++] = new Op(0xF3C, 0xFFC, 0, 0, 7, 106);      // SCPY
		// SCF/SZF/SDF/EI (0xFFF) must come before SET (0xFF0)
		ops[n++] = new Op(0xF41, 0xFFF, 0, 0, 7, 52);       // SCF
		ops[n++] = new Op(0xF42, 0xFFF, 0, 0, 7, 54);       // SZF
		ops[n++] = new Op(0xF44, 0xFFF, 0, 0, 7, 56);       // SDF
		ops[n++] = new Op(0xF48, 0xFFF, 0, 0, 7, 58);       // EI
		ops[n++] = new Op(0xF40, 0xFF0, 0, 0, 7, 50);       // SET
		// RCF/RZF/RDF/DI (0xFFF) must come before RST (0xFF0)
		ops[n++] = new Op(0xF5E, 0xFFF, 0, 0, 7, 53);       // RCF
		ops[n++] = new Op(0xF5D, 0xFFF, 0, 0, 7, 55);       // RZF
		ops[n++] = new Op(0xF5B, 0xFFF, 0, 0, 7, 57);       // RDF
		ops[n++] = new Op(0xF57, 0xFFF, 0, 0, 7, 59);       // DI
		ops[n++] = new Op(0xF50, 0xFF0, 0, 0, 7, 51);       // RST
		ops[n++] = new Op(0xF60, 0xFF0, 0, 0, 7, 101);      // INC_MN
		ops[n++] = new Op(0xF70, 0xFF0, 0, 0, 7, 102);      // DEC_MN
		ops[n++] = new Op(0xF80, 0xFF0, 0, 0, 5, 43);       // LD_MN_A
		ops[n++] = new Op(0xF90, 0xFF0, 0, 0, 5, 44);       // LD_MN_B
		ops[n++] = new Op(0xFA0, 0xFF0, 0, 0, 5, 41);       // LD_A_MN
		ops[n++] = new Op(0xFB0, 0xFF0, 0, 0, 5, 42);       // LD_B_MN
		// FC* group: specific 0xFFF before 0xFFC
		ops[n++] = new Op(0xFC4, 0xFFF, 0, 0, 5, 63);       // PUSH_XP
		ops[n++] = new Op(0xFC5, 0xFFF, 0, 0, 5, 64);       // PUSH_XH
		ops[n++] = new Op(0xFC6, 0xFFF, 0, 0, 5, 65);       // PUSH_XL
		ops[n++] = new Op(0xFC7, 0xFFF, 0, 0, 5, 66);       // PUSH_YP
		ops[n++] = new Op(0xFC8, 0xFFF, 0, 0, 5, 67);       // PUSH_YH
		ops[n++] = new Op(0xFC9, 0xFFF, 0, 0, 5, 68);       // PUSH_YL
		ops[n++] = new Op(0xFCA, 0xFFF, 0, 0, 5, 69);       // PUSH_F
		ops[n++] = new Op(0xFCB, 0xFFF, 0, 0, 5, 61);       // DEC_SP
		ops[n++] = new Op(0xFC0, 0xFFC, 0, 0, 5, 62);       // PUSH_R
		// FD* group
		ops[n++] = new Op(0xFD4, 0xFFF, 0, 0, 5, 71);       // POP_XP
		ops[n++] = new Op(0xFD5, 0xFFF, 0, 0, 5, 72);       // POP_XH
		ops[n++] = new Op(0xFD6, 0xFFF, 0, 0, 5, 73);       // POP_XL
		ops[n++] = new Op(0xFD7, 0xFFF, 0, 0, 5, 74);       // POP_YP
		ops[n++] = new Op(0xFD8, 0xFFF, 0, 0, 5, 75);       // POP_YH
		ops[n++] = new Op(0xFD9, 0xFFF, 0, 0, 5, 76);       // POP_YL
		ops[n++] = new Op(0xFDA, 0xFFF, 0, 0, 5, 77);       // POP_F
		ops[n++] = new Op(0xFDB, 0xFFF, 0, 0, 5, 60);       // INC_SP
		ops[n++] = new Op(0xFD0, 0xFFC, 0, 0, 5, 70);       // POP_R
		// FE* group
		ops[n++] = new Op(0xFE0, 0xFFC, 0, 0, 5, 78);       // LD_SPH_R
		ops[n++] = new Op(0xFE4, 0xFFC, 0, 0, 5, 80);       // LD_R_SPH
		// FF* group
		ops[n++] = new Op(0xFF0, 0xFFC, 0, 0, 5, 79);       // LD_SPL_R
		ops[n++] = new Op(0xFF4, 0xFFC, 0, 0, 5, 81);       // LD_R_SPL

		// Trim array to actual size
		ops = java.util.Arrays.copyOf(ops, n);
	}

	// --- Flag helpers ---
	private int C()
	{
		return (flags & FLAG_C) != 0 ? 1 : 0;
	}

	private int Z()
	{
		return (flags & FLAG_Z) != 0 ? 1 : 0;
	}

	private int D()
	{
		return (flags & FLAG_D) != 0 ? 1 : 0;
	}

	private int I()
	{
		return (flags & FLAG_I) != 0 ? 1 : 0;
	}

	private void setC()
	{
		flags |= FLAG_C;
	}

	private void clearC()
	{
		flags &= ~FLAG_C;
	}

	private void setZ()
	{
		flags |= FLAG_Z;
	}

	private void clearZ()
	{
		flags &= ~FLAG_Z;
	}

	private void setD()
	{
		flags |= FLAG_D;
	}

	private void clearD()
	{
		flags &= ~FLAG_D;
	}

	private void setI()
	{
		flags |= FLAG_I;
	}

	private void clearI()
	{
		flags &= ~FLAG_I;
	}

	// --- Register quartet accessor ---
	private int getRq(int i)
	{
		switch (i & 0x3)
		{
			case 0: return a;
			case 1: return b;
			case 2: return getMemory(x);
			case 3: return getMemory(y);
			default: return 0;
		}
	}

	private void setRq(int i, int v)
	{
		v &= 0xF;
		switch (i & 0x3)
		{
			case 0: a = v; break;
			case 1: b = v; break;
			case 2: setMemory(x, v); break;
			case 3: setMemory(y, v); break;
		}
	}

	// --- Memory access ---
	public int getMemory(int n)
	{
		n &= 0xFFF;
		if (n >= MEM_IO_ADDR && n < MEM_IO_ADDR + MEM_IO_SIZE)
		{
			return getIo(n);
		}
		return memory[n] & 0xF;
	}

	public void setMemory(int n, int v)
	{
		n &= 0xFFF;
		v &= 0xF;
		if (n >= MEM_IO_ADDR && n < MEM_IO_ADDR + MEM_IO_SIZE)
		{
			setIo(n, v);
		}
		else if ((n >= MEM_DISPLAY1_ADDR && n < MEM_DISPLAY1_ADDR + MEM_DISPLAY1_SIZE) ||
			(n >= MEM_DISPLAY2_ADDR && n < MEM_DISPLAY2_ADDR + MEM_DISPLAY2_SIZE))
		{
			memory[n] = v;
			setLcd(n - MEM_DISPLAY1_ADDR, v);
		}
		else
		{
			memory[n] = v;
		}
	}

	// --- I/O register access ---
	private int getIo(int n)
	{
		switch (n)
		{
			// Interrupt factor flags: reading clears the flag
			case 0xF00:
			case 0xF01:
			case 0xF02:
			case 0xF03:
			case 0xF04:
			case 0xF05:
			{
				int v = memory[n] & 0xF;
				memory[n] = 0;
				return v;
			}
			// Interrupt masks
			case 0xF10:
			case 0xF11:
			case 0xF12:
			case 0xF13:
			case 0xF14:
			case 0xF15:
				return memory[n] & 0xF;
			// Clock timer data
			case 0xF20:
			case 0xF21:
				return memory[n] & 0xF;
			// Stopwatch timer data
			case 0xF22:
			case 0xF23:
				return memory[n] & 0xF;
			// Prog timer data (read from field, not memory)
			case 0xF24:
				return progTimerData & 0xF;
			case 0xF25:
				return (progTimerData >> 4) & 0xF;
			// Prog timer reload data (read from field)
			case 0xF26:
				return progTimerRld & 0xF;
			case 0xF27:
				return (progTimerRld >> 4) & 0xF;
			// Input ports
			case 0xF40:
				return inputStates[0] & 0xF;
			case 0xF41:
				return memory[n] & 0xF;
			case 0xF42:
				return inputStates[1] & 0xF;
			// Output ports
			case 0xF50:
			case 0xF51:
			case 0xF52:
			case 0xF53:
			case 0xF54:
				return memory[n] & 0xF;
			// CPU/OSC3 control
			case 0xF70:
				return memory[n] & 0xF;
			// LCD control
			case 0xF71:
				return memory[n] & 0xF;
			// LCD contrast
			case 0xF72:
				return memory[n] & 0xF;
			// SVD: voltage always OK
			case 0xF73:
				return memory[n] & 0x7;
			// Buzzer ctrl1
			case 0xF74:
				return memory[n] & 0x7;
			// Buzzer ctrl2
			case 0xF75:
				return memory[n] & 0x3;
			// Prog timer control
			case 0xF78:
				return progTimerEnabled ? 1 : 0;
			default:
				return memory[n] & 0xF;
		}
	}

	private void setIo(int n, int v)
	{
		v &= 0xF;
		switch (n)
		{
			// Interrupt factor flags
			case 0xF00:
			case 0xF01:
			case 0xF02:
			case 0xF03:
			case 0xF04:
			case 0xF05:
				memory[n] = v;
				break;
			// Interrupt masks
			case 0xF10:
			case 0xF11:
			case 0xF12:
			case 0xF13:
			case 0xF14:
			case 0xF15:
				memory[n] = v;
				break;
			// Clock timer data (read-only, ignore writes)
			case 0xF20:
			case 0xF21:
				break;
			// Stopwatch timer data (read-only, ignore writes)
			case 0xF22:
			case 0xF23:
				break;
			// Prog timer data (read-only, ignore writes)
			case 0xF24:
			case 0xF25:
				break;
			// Prog timer reload value
			case 0xF26:
				progTimerRld = (progTimerRld & 0xF0) | (v & 0xF);
				break;
			case 0xF27:
				progTimerRld = (progTimerRld & 0x0F) | ((v & 0xF) << 4);
				break;
			// Input relation register
			case 0xF41:
				memory[n] = v;
				break;
			// Output ports
			case 0xF50:
			case 0xF51:
			case 0xF52:
			case 0xF53:
				memory[n] = v;
				break;
			case 0xF54:
				memory[n] = v;
				// R40-R43 controls buzzer: bit 3 is active LOW
				hal.playFrequency((v & 0x8) == 0);
				break;
			// CPU/OSC3 control
			case 0xF70:
				memory[n] = v;
				// Bit 3: switch between OSC1 and OSC3
				if ((v & 0x8) != 0)
				{
					cpuFrequency = OSC3_FREQUENCY;
				}
				else
				{
					cpuFrequency = OSC1_FREQUENCY;
				}
				break;
			// LCD control
			case 0xF71:
				memory[n] = v;
				break;
			// LCD contrast
			case 0xF72:
				memory[n] = v;
				break;
			// SVD
			case 0xF73:
				memory[n] = v & 0x7;
				break;
			// Buzzer ctrl1
			case 0xF74:
				memory[n] = v;
				setBuzzerFreq(v & 0x7);
				break;
			// Buzzer ctrl2
			case 0xF75:
				memory[n] = v & 0x3;
				break;
			// Prog timer control
			case 0xF78:
				memory[n] = v;
				if ((v & 0x2) != 0)
				{
					// Reload
					progTimerData = progTimerRld;
				}
				if ((v & 0x1) != 0 && !progTimerEnabled)
				{
					// Starting the timer — record timestamp
					progTimerTs = tickCounter;
				}
				progTimerEnabled = (v & 0x1) != 0;
				break;
			default:
				memory[n] = v;
				break;
		}
	}

	// --- LCD ---
	private void setLcd(int n, int v)
	{
		int seg = ((n & 0x7F) >> 1);
		int com0 = (((n & 0x80) >> 7) * 8 + (n & 0x1) * 4);
		for (int i = 0; i < 4; i++)
		{
			hwSetLcdPin(seg, com0 + i, ((v >> i) & 0x1) != 0);
		}
	}

	private void hwSetLcdPin(int seg, int com, boolean val)
	{
		if (seg < SEG_POS.length && SEG_POS[seg] < LCD_WIDTH)
		{
			hal.setLcdMatrix(SEG_POS[seg], com, val);
		}
		else if (seg < SEG_POS.length)
		{
			if (seg == 8 && com < 4)
			{
				hal.setLcdIcon(com, val);
			}
			else if (seg == 28 && com >= 12)
			{
				hal.setLcdIcon(com - 8, val);
			}
		}
	}

	// --- Buzzer ---
	private void setBuzzerFreq(int freq)
	{
		if (freq >= 0 && freq < BUZZER_FREQS.length)
		{
			hal.setFrequency(BUZZER_FREQS[freq]);
		}
	}

	// --- Interrupt generation ---
	private void generateInterrupt(int intSlot, int bit)
	{
		// Set the factor flag bit
		memory[interrupts[intSlot].factorFlagReg] |= (1 << bit);
		// Check if masked
		if ((memory[interrupts[intSlot].maskReg] & (1 << bit)) != 0)
		{
			interrupts[intSlot].triggered = true;
		}
	}

	// --- Input pin ---
	private void setInputPin(int pin, int state)
	{
		int port = (pin >> 2) & 1;
		int bit = pin & 0x3;
		int oldState = (inputStates[port] >> bit) & 0x1;
		if (state != oldState)
		{
			if (port == 0)
			{
				// Active HIGH/LOW depending on relation register
				if (state != ((memory[0xF41] >> bit) & 0x1))
				{
					generateInterrupt(INT_K00_K03, bit);
				}
			}
			else
			{
				// Active LOW
				if (state == 0)
				{
					generateInterrupt(INT_K10_K13, bit);
				}
			}
		}
		inputStates[port] = (inputStates[port] & ~(0x1 << bit)) | (state << bit);
	}

	// --- Public button interface ---
	public void setButton(int btn, boolean pressed)
	{
		int pinState = pressed ? 0 : 1; // active LOW
		switch (btn)
		{
			case BTN_LEFT:
				setInputPin(PIN_K02, pinState);
				break;
			case BTN_MIDDLE:
				setInputPin(PIN_K01, pinState);
				break;
			case BTN_RIGHT:
				setInputPin(PIN_K00, pinState);
				break;
		}
	}

	// --- Timing ---
	private long waitForCycles(long since, int cycles)
	{
		scaledCycleAccumulator += (long) cycles * TICK_FREQUENCY;
		long ticksPending = scaledCycleAccumulator / cpuFrequency;
		if (ticksPending > 0)
		{
			tickCounter += ticksPending;
			scaledCycleAccumulator -= ticksPending * cpuFrequency;
		}
		if (speedRatio == 0)
		{
			return hal.getTimestamp();
		}
		long deadline = since + ((long) cycles * tsFreq) / ((long) cpuFrequency * speedRatio);
		hal.sleepUntil(deadline);
		return deadline;
	}

	// --- Timers ---
	// Clock timer data register layout:
	// 0xF20 (data1): bit0=128Hz, bit1=64Hz, bit2=32Hz, bit3=16Hz
	// 0xF21 (data2): bit0=8Hz, bit1=4Hz, bit2=2Hz, bit3=1Hz
	// Each timer toggles its bit. Interrupts on falling edge for select frequencies.
	private void handleTimers()
	{
		// 2Hz → toggles data2 bit3, interrupt on falling edge (1Hz)
		if (tickCounter - clkTimer2hzTs >= TIMER_2HZ)
		{
			do
			{
				clkTimer2hzTs += TIMER_2HZ;
			}
			while (tickCounter - clkTimer2hzTs >= TIMER_2HZ);
			memory[0xF21] ^= (1 << 3);
			if (((memory[0xF21] >> 3) & 1) == 0)
			{
				generateInterrupt(INT_CLOCK_TIMER, 3);
			}
		}

		// 4Hz → toggles data2 bit2, interrupt on falling edge (2Hz)
		if (tickCounter - clkTimer4hzTs >= TIMER_4HZ)
		{
			do
			{
				clkTimer4hzTs += TIMER_4HZ;
			}
			while (tickCounter - clkTimer4hzTs >= TIMER_4HZ);
			memory[0xF21] ^= (1 << 2);
			if (((memory[0xF21] >> 2) & 1) == 0)
			{
				generateInterrupt(INT_CLOCK_TIMER, 2);
			}
		}

		// 8Hz → toggles data2 bit1 (no interrupt)
		if (tickCounter - clkTimer8hzTs >= TIMER_8HZ)
		{
			do
			{
				clkTimer8hzTs += TIMER_8HZ;
			}
			while (tickCounter - clkTimer8hzTs >= TIMER_8HZ);
			memory[0xF21] ^= (1 << 1);
		}

		// 16Hz → toggles data2 bit0, interrupt on falling edge (8Hz)
		if (tickCounter - clkTimer16hzTs >= TIMER_16HZ)
		{
			do
			{
				clkTimer16hzTs += TIMER_16HZ;
			}
			while (tickCounter - clkTimer16hzTs >= TIMER_16HZ);
			memory[0xF21] ^= (1 << 0);
			if ((memory[0xF21] & 1) == 0)
			{
				generateInterrupt(INT_CLOCK_TIMER, 1);
			}
		}

		// 32Hz → toggles data1 bit3 (no interrupt)
		if (tickCounter - clkTimer32hzTs >= TIMER_32HZ)
		{
			do
			{
				clkTimer32hzTs += TIMER_32HZ;
			}
			while (tickCounter - clkTimer32hzTs >= TIMER_32HZ);
			memory[0xF20] ^= (1 << 3);
		}

		// 64Hz → toggles data1 bit2, interrupt on falling edge (32Hz)
		if (tickCounter - clkTimer64hzTs >= TIMER_64HZ)
		{
			do
			{
				clkTimer64hzTs += TIMER_64HZ;
			}
			while (tickCounter - clkTimer64hzTs >= TIMER_64HZ);
			memory[0xF20] ^= (1 << 2);
			if (((memory[0xF20] >> 2) & 1) == 0)
			{
				generateInterrupt(INT_CLOCK_TIMER, 0);
			}
		}

		// 128Hz → toggles data1 bit1 (no interrupt)
		if (tickCounter - clkTimer128hzTs >= TIMER_128HZ)
		{
			do
			{
				clkTimer128hzTs += TIMER_128HZ;
			}
			while (tickCounter - clkTimer128hzTs >= TIMER_128HZ);
			memory[0xF20] ^= (1 << 1);
		}

		// 256Hz → toggles data1 bit0 (no interrupt)
		if (tickCounter - clkTimer256hzTs >= TIMER_256HZ)
		{
			do
			{
				clkTimer256hzTs += TIMER_256HZ;
			}
			while (tickCounter - clkTimer256hzTs >= TIMER_256HZ);
			memory[0xF20] ^= (1 << 0);
		}

		// Prog timer (256Hz clock)
		if (progTimerEnabled && tickCounter - progTimerTs >= TIMER_256HZ)
		{
			do
			{
				progTimerTs += TIMER_256HZ;
				progTimerData = (progTimerData - 1) & 0xFF;
				if (progTimerData == 0)
				{
					progTimerData = progTimerRld;
					generateInterrupt(INT_PROG_TIMER, 0);
				}
			}
			while (tickCounter - progTimerTs >= TIMER_256HZ);
		}
	}

	// --- Interrupt processing ---
	private void processInterrupts()
	{
		for (int i = 0; i < 6; i++)
		{
			if (interrupts[i].triggered)
			{
				setMemory((sp - 1) & 0xFF, (pc >> 8) & 0xF);
				setMemory((sp - 2) & 0xFF, (pc >> 4) & 0xF);
				setMemory((sp - 3) & 0xFF, pc & 0xF);
				sp = (sp - 3) & 0xFF;
				clearI();
				np = ((np >> 4) & 0x1) << 4 | 1;
				pc = (((pc >> 12) & 0x1) << 12) | (1 << 8) | interrupts[i].vector;
				callDepth++;
				cpuHalted = false;
				refTs = waitForCycles(refTs, 12);
				interrupts[i].triggered = false;
				return;
			}
		}
	}

	// --- Main step ---
	public boolean step()
	{
		int opIdx = -1;

		if (!cpuHalted)
		{
			int op = program[pc];
			// Linear search for matching opcode
			for (int i = 0; i < ops.length; i++)
			{
				if ((op & ops[i].mask) == ops[i].code)
				{
					opIdx = i;
					break;
				}
			}
			if (opIdx < 0)
			{
				return true; // unknown opcode
			}

			nextPc = (pc + 1) & 0x1FFF;

			// Wait for cycles (timing)
			refTs = waitForCycles(refTs, previousCycles);

			// Decode arguments
			int arg0, arg1;
			if (ops[opIdx].maskArg0 != 0)
			{
				arg0 = (op & ops[opIdx].maskArg0) >> ops[opIdx].shiftArg0;
				arg1 = op & ~(ops[opIdx].mask | ops[opIdx].maskArg0);
			}
			else
			{
				arg0 = (op & ~ops[opIdx].mask) >> ops[opIdx].shiftArg0;
				arg1 = 0;
			}

			executeOp(ops[opIdx].index, arg0, arg1);

			pc = nextPc;
			previousCycles = ops[opIdx].cycles;

			if (ops[opIdx].index != 0)
			{
				// Not PSET
				np = (pc >> 8) & 0x1F;
			}
		}
		else
		{
			refTs = waitForCycles(refTs, 5);
			previousCycles = 0;
		}

		handleTimers();

		// Process interrupts (not after PSET(0) or EI(58))
		if (I() != 0 && opIdx != -1 && ops[opIdx].index != 0 && ops[opIdx].index != 58)
		{
			processInterrupts();
		}
		else if (I() != 0 && opIdx == -1)
		{
			// CPU was halted, still process interrupts
			processInterrupts();
		}

		return false;
	}

	// --- Instruction execution ---
	private void executeOp(int idx, int arg0, int arg1)
	{
		int tmp;
		switch (idx)
		{
			case 0: // PSET
				np = arg0;
				break;
			case 1: // JP
				nextPc = arg0 | (np << 8);
				break;
			case 2: // JP_C
				if ((flags & FLAG_C) != 0)
				{
					nextPc = arg0 | (np << 8);
				}
				break;
			case 3: // JP_NC
				if ((flags & FLAG_C) == 0)
				{
					nextPc = arg0 | (np << 8);
				}
				break;
			case 4: // JP_Z
				if ((flags & FLAG_Z) != 0)
				{
					nextPc = arg0 | (np << 8);
				}
				break;
			case 5: // JP_NZ
				if ((flags & FLAG_Z) == 0)
				{
					nextPc = arg0 | (np << 8);
				}
				break;
			case 6: // JPBA
				nextPc = a | (b << 4) | (np << 8);
				break;
			case 7: // CALL
			{
				pc = (pc + 1) & 0x1FFF;
				setMemory((sp - 1) & 0xFF, (pc >> 8) & 0xF);
				setMemory((sp - 2) & 0xFF, (pc >> 4) & 0xF);
				setMemory((sp - 3) & 0xFF, pc & 0xF);
				sp = (sp - 3) & 0xFF;
				nextPc = (((pc >> 12) & 0x1) << 12) | ((np & 0xF) << 8) | arg0;
				callDepth++;
				break;
			}
			case 8: // CALZ
			{
				pc = (pc + 1) & 0x1FFF;
				setMemory((sp - 1) & 0xFF, (pc >> 8) & 0xF);
				setMemory((sp - 2) & 0xFF, (pc >> 4) & 0xF);
				setMemory((sp - 3) & 0xFF, pc & 0xF);
				sp = (sp - 3) & 0xFF;
				nextPc = (((pc >> 12) & 0x1) << 12) | (0 << 8) | arg0;
				callDepth++;
				break;
			}
			case 9: // RET
			{
				nextPc = getMemory(sp)
					| (getMemory((sp + 1) & 0xFF) << 4)
					| (getMemory((sp + 2) & 0xFF) << 8)
					| (((pc >> 12) & 0x1) << 12);
				sp = (sp + 3) & 0xFF;
				if (callDepth > 0) callDepth--;
				break;
			}
			case 10: // RETS
			{
				nextPc = getMemory(sp)
					| (getMemory((sp + 1) & 0xFF) << 4)
					| (getMemory((sp + 2) & 0xFF) << 8)
					| (((pc >> 12) & 0x1) << 12);
				sp = (sp + 3) & 0xFF;
				nextPc = (nextPc + 1) & 0x1FFF;
				if (callDepth > 0) callDepth--;
				break;
			}
			case 11: // RETD
			{
				nextPc = getMemory(sp)
					| (getMemory((sp + 1) & 0xFF) << 4)
					| (getMemory((sp + 2) & 0xFF) << 8)
					| (((pc >> 12) & 0x1) << 12);
				sp = (sp + 3) & 0xFF;
				setMemory(x, arg0 & 0xF);
				setMemory(((x + 1) & 0xFF) | (((x >> 8) & 0xF) << 8), (arg0 >> 4) & 0xF);
				x = ((x + 2) & 0xFF) | (((x >> 8) & 0xF) << 8);
				if (callDepth > 0) callDepth--;
				break;
			}
			case 12: // NOP5
				break;
			case 13: // NOP7
				break;
			case 14: // HALT
				cpuHalted = true;
				break;
			case 15: // INC_X
				x = ((x + 1) & 0xFF) | (((x >> 8) & 0xF) << 8);
				break;
			case 16: // INC_Y
				y = ((y + 1) & 0xFF) | (((y >> 8) & 0xF) << 8);
				break;
			case 17: // LD_X
				x = arg0 | (((x >> 8) & 0xF) << 8);
				break;
			case 18: // LD_Y
				y = arg0 | (((y >> 8) & 0xF) << 8);
				break;
			case 19: // LD_XP_R
				x = (x & 0xFF) | (getRq(arg0) << 8);
				break;
			case 20: // LD_XH_R
				x = (x & 0xF) | (getRq(arg0) << 4) | (((x >> 8) & 0xF) << 8);
				break;
			case 21: // LD_XL_R
				x = getRq(arg0) | (((x >> 4) & 0xF) << 4) | (((x >> 8) & 0xF) << 8);
				break;
			case 22: // LD_YP_R
				y = (y & 0xFF) | (getRq(arg0) << 8);
				break;
			case 23: // LD_YH_R
				y = (y & 0xF) | (getRq(arg0) << 4) | (((y >> 8) & 0xF) << 8);
				break;
			case 24: // LD_YL_R
				y = getRq(arg0) | (((y >> 4) & 0xF) << 4) | (((y >> 8) & 0xF) << 8);
				break;
			case 25: // LD_R_XP
				setRq(arg0, (x >> 8) & 0xF);
				break;
			case 26: // LD_R_XH
				setRq(arg0, (x >> 4) & 0xF);
				break;
			case 27: // LD_R_XL
				setRq(arg0, x & 0xF);
				break;
			case 28: // LD_R_YP
				setRq(arg0, (y >> 8) & 0xF);
				break;
			case 29: // LD_R_YH
				setRq(arg0, (y >> 4) & 0xF);
				break;
			case 30: // LD_R_YL
				setRq(arg0, y & 0xF);
				break;
			case 31: // ADC_XH
			{
				tmp = ((x >> 4) & 0xF) + arg0 + C();
				x = (x & 0xF) | ((tmp & 0xF) << 4) | (((x >> 8) & 0xF) << 8);
				if ((tmp >> 4) != 0) setC(); else clearC();
				if ((tmp & 0xF) == 0) setZ(); else clearZ();
				break;
			}
			case 32: // ADC_XL
			{
				tmp = (x & 0xF) + arg0 + C();
				x = (tmp & 0xF) | (((x >> 4) & 0xF) << 4) | (((x >> 8) & 0xF) << 8);
				if ((tmp >> 4) != 0) setC(); else clearC();
				if ((tmp & 0xF) == 0) setZ(); else clearZ();
				break;
			}
			case 33: // ADC_YH
			{
				tmp = ((y >> 4) & 0xF) + arg0 + C();
				y = (y & 0xF) | ((tmp & 0xF) << 4) | (((y >> 8) & 0xF) << 8);
				if ((tmp >> 4) != 0) setC(); else clearC();
				if ((tmp & 0xF) == 0) setZ(); else clearZ();
				break;
			}
			case 34: // ADC_YL
			{
				tmp = (y & 0xF) + arg0 + C();
				y = (tmp & 0xF) | (((y >> 4) & 0xF) << 4) | (((y >> 8) & 0xF) << 8);
				if ((tmp >> 4) != 0) setC(); else clearC();
				if ((tmp & 0xF) == 0) setZ(); else clearZ();
				break;
			}
			case 35: // CP_XH
			{
				int xh = (x >> 4) & 0xF;
				if (xh < arg0) setC(); else clearC();
				if (xh == arg0) setZ(); else clearZ();
				break;
			}
			case 36: // CP_XL
			{
				int xl = x & 0xF;
				if (xl < arg0) setC(); else clearC();
				if (xl == arg0) setZ(); else clearZ();
				break;
			}
			case 37: // CP_YH
			{
				int yh = (y >> 4) & 0xF;
				if (yh < arg0) setC(); else clearC();
				if (yh == arg0) setZ(); else clearZ();
				break;
			}
			case 38: // CP_YL
			{
				int yl = y & 0xF;
				if (yl < arg0) setC(); else clearC();
				if (yl == arg0) setZ(); else clearZ();
				break;
			}
			case 39: // LD_R_I
				setRq(arg0, arg1);
				break;
			case 40: // LD_R_Q
				setRq(arg0, getRq(arg1));
				break;
			case 41: // LD_A_MN
				a = getMemory(arg0);
				break;
			case 42: // LD_B_MN
				b = getMemory(arg0);
				break;
			case 43: // LD_MN_A
				setMemory(arg0, a);
				break;
			case 44: // LD_MN_B
				setMemory(arg0, b);
				break;
			case 45: // LDPX_MX
				setMemory(x, arg0);
				x = ((x + 1) & 0xFF) | (((x >> 8) & 0xF) << 8);
				break;
			case 46: // LDPX_R
				setRq(arg0, getRq(arg1));
				x = ((x + 1) & 0xFF) | (((x >> 8) & 0xF) << 8);
				break;
			case 47: // LDPY_MY
				setMemory(y, arg0);
				y = ((y + 1) & 0xFF) | (((y >> 8) & 0xF) << 8);
				break;
			case 48: // LDPY_R
				setRq(arg0, getRq(arg1));
				y = ((y + 1) & 0xFF) | (((y >> 8) & 0xF) << 8);
				break;
			case 49: // LBPX
				setMemory(x, arg0 & 0xF);
				setMemory(((x + 1) & 0xFF) | (((x >> 8) & 0xF) << 8), (arg0 >> 4) & 0xF);
				x = ((x + 2) & 0xFF) | (((x >> 8) & 0xF) << 8);
				break;
			case 50: // SET
				flags |= arg0;
				break;
			case 51: // RST
				flags &= arg0;
				break;
			case 52: // SCF
				setC();
				break;
			case 53: // RCF
				clearC();
				break;
			case 54: // SZF
				setZ();
				break;
			case 55: // RZF
				clearZ();
				break;
			case 56: // SDF
				setD();
				break;
			case 57: // RDF
				clearD();
				break;
			case 58: // EI
				setI();
				break;
			case 59: // DI
				clearI();
				break;
			case 60: // INC_SP
				sp = (sp + 1) & 0xFF;
				break;
			case 61: // DEC_SP
				sp = (sp - 1) & 0xFF;
				break;
			case 62: // PUSH_R
				sp = (sp - 1) & 0xFF;
				setMemory(sp, getRq(arg0));
				break;
			case 63: // PUSH_XP
				sp = (sp - 1) & 0xFF;
				setMemory(sp, (x >> 8) & 0xF);
				break;
			case 64: // PUSH_XH
				sp = (sp - 1) & 0xFF;
				setMemory(sp, (x >> 4) & 0xF);
				break;
			case 65: // PUSH_XL
				sp = (sp - 1) & 0xFF;
				setMemory(sp, x & 0xF);
				break;
			case 66: // PUSH_YP
				sp = (sp - 1) & 0xFF;
				setMemory(sp, (y >> 8) & 0xF);
				break;
			case 67: // PUSH_YH
				sp = (sp - 1) & 0xFF;
				setMemory(sp, (y >> 4) & 0xF);
				break;
			case 68: // PUSH_YL
				sp = (sp - 1) & 0xFF;
				setMemory(sp, y & 0xF);
				break;
			case 69: // PUSH_F
				sp = (sp - 1) & 0xFF;
				setMemory(sp, flags);
				break;
			case 70: // POP_R
				setRq(arg0, getMemory(sp));
				sp = (sp + 1) & 0xFF;
				break;
			case 71: // POP_XP
				x = (x & 0xF) | (((x >> 4) & 0xF) << 4) | (getMemory(sp) << 8);
				sp = (sp + 1) & 0xFF;
				break;
			case 72: // POP_XH
				x = (x & 0xF) | (getMemory(sp) << 4) | (((x >> 8) & 0xF) << 8);
				sp = (sp + 1) & 0xFF;
				break;
			case 73: // POP_XL
				x = getMemory(sp) | (((x >> 4) & 0xF) << 4) | (((x >> 8) & 0xF) << 8);
				sp = (sp + 1) & 0xFF;
				break;
			case 74: // POP_YP
				y = (y & 0xF) | (((y >> 4) & 0xF) << 4) | (getMemory(sp) << 8);
				sp = (sp + 1) & 0xFF;
				break;
			case 75: // POP_YH
				y = (y & 0xF) | (getMemory(sp) << 4) | (((y >> 8) & 0xF) << 8);
				sp = (sp + 1) & 0xFF;
				break;
			case 76: // POP_YL
				y = getMemory(sp) | (((y >> 4) & 0xF) << 4) | (((y >> 8) & 0xF) << 8);
				sp = (sp + 1) & 0xFF;
				break;
			case 77: // POP_F
				flags = getMemory(sp);
				sp = (sp + 1) & 0xFF;
				break;
			case 78: // LD_SPH_R
				sp = (sp & 0xF) | (getRq(arg0) << 4);
				break;
			case 79: // LD_SPL_R
				sp = getRq(arg0) | (((sp >> 4) & 0xF) << 4);
				break;
			case 80: // LD_R_SPH
				setRq(arg0, (sp >> 4) & 0xF);
				break;
			case 81: // LD_R_SPL
				setRq(arg0, sp & 0xF);
				break;
			case 82: // ADD_R_I
			{
				tmp = getRq(arg0) + arg1;
				if (D() != 0)
				{
					if (tmp >= 10) { setRq(arg0, (tmp - 10) & 0xF); setC(); }
					else { setRq(arg0, tmp); clearC(); }
				}
				else
				{
					setRq(arg0, tmp & 0xF);
					if ((tmp >> 4) != 0) setC(); else clearC();
				}
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 83: // ADD_R_Q
			{
				tmp = getRq(arg0) + getRq(arg1);
				if (D() != 0)
				{
					if (tmp >= 10) { setRq(arg0, (tmp - 10) & 0xF); setC(); }
					else { setRq(arg0, tmp); clearC(); }
				}
				else
				{
					setRq(arg0, tmp & 0xF);
					if ((tmp >> 4) != 0) setC(); else clearC();
				}
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 84: // ADC_R_I
			{
				tmp = getRq(arg0) + arg1 + C();
				if (D() != 0)
				{
					if (tmp >= 10) { setRq(arg0, (tmp - 10) & 0xF); setC(); }
					else { setRq(arg0, tmp); clearC(); }
				}
				else
				{
					setRq(arg0, tmp & 0xF);
					if ((tmp >> 4) != 0) setC(); else clearC();
				}
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 85: // ADC_R_Q
			{
				tmp = getRq(arg0) + getRq(arg1) + C();
				if (D() != 0)
				{
					if (tmp >= 10) { setRq(arg0, (tmp - 10) & 0xF); setC(); }
					else { setRq(arg0, tmp); clearC(); }
				}
				else
				{
					setRq(arg0, tmp & 0xF);
					if ((tmp >> 4) != 0) setC(); else clearC();
				}
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 86: // SUB
			{
				tmp = (getRq(arg0) - getRq(arg1)) & 0xFF;
				if (D() != 0)
				{
					if ((tmp >> 4) != 0) setRq(arg0, (tmp - 6) & 0xF);
					else setRq(arg0, tmp);
				}
				else
				{
					setRq(arg0, tmp & 0xF);
				}
				if ((tmp >> 4) != 0) setC(); else clearC();
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 87: // SBC_R_I
			{
				tmp = (getRq(arg0) - arg1 - C()) & 0xFF;
				if (D() != 0)
				{
					if ((tmp >> 4) != 0) setRq(arg0, (tmp - 6) & 0xF);
					else setRq(arg0, tmp);
				}
				else
				{
					setRq(arg0, tmp & 0xF);
				}
				if ((tmp >> 4) != 0) setC(); else clearC();
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 88: // SBC_R_Q
			{
				tmp = (getRq(arg0) - getRq(arg1) - C()) & 0xFF;
				if (D() != 0)
				{
					if ((tmp >> 4) != 0) setRq(arg0, (tmp - 6) & 0xF);
					else setRq(arg0, tmp);
				}
				else
				{
					setRq(arg0, tmp & 0xF);
				}
				if ((tmp >> 4) != 0) setC(); else clearC();
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 89: // AND_R_I
				setRq(arg0, getRq(arg0) & arg1);
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			case 90: // AND_R_Q
				setRq(arg0, getRq(arg0) & getRq(arg1));
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			case 91: // OR_R_I
				setRq(arg0, getRq(arg0) | arg1);
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			case 92: // OR_R_Q
				setRq(arg0, getRq(arg0) | getRq(arg1));
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			case 93: // XOR_R_I
				setRq(arg0, getRq(arg0) ^ arg1);
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			case 94: // XOR_R_Q
				setRq(arg0, getRq(arg0) ^ getRq(arg1));
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
			case 95: // CP_R_I
				if (getRq(arg0) < arg1) setC(); else clearC();
				if (getRq(arg0) == arg1) setZ(); else clearZ();
				break;
			case 96: // CP_R_Q
				if (getRq(arg0) < getRq(arg1)) setC(); else clearC();
				if (getRq(arg0) == getRq(arg1)) setZ(); else clearZ();
				break;
			case 97: // FAN_R_I
				if ((getRq(arg0) & arg1) == 0) setZ(); else clearZ();
				break;
			case 98: // FAN_R_Q
				if ((getRq(arg0) & getRq(arg1)) == 0) setZ(); else clearZ();
				break;
			case 99: // RLC
			{
				int oldVal = getRq(arg0);
				tmp = (oldVal << 1) | C();
				if ((oldVal & 0x8) != 0) setC(); else clearC();
				setRq(arg0, tmp & 0xF);
				break;
			}
			case 100: // RRC
			{
				int oldVal = getRq(arg0);
				tmp = (oldVal >> 1) | (C() << 3);
				if ((oldVal & 0x1) != 0) setC(); else clearC();
				setRq(arg0, tmp & 0xF);
				break;
			}
			case 101: // INC_MN
			{
				tmp = getMemory(arg0) + 1;
				setMemory(arg0, tmp & 0xF);
				if ((tmp >> 4) != 0) setC(); else clearC();
				if (getMemory(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 102: // DEC_MN
			{
				tmp = (getMemory(arg0) - 1) & 0xFF;
				setMemory(arg0, tmp & 0xF);
				if ((tmp >> 4) != 0) setC(); else clearC();
				if (getMemory(arg0) == 0) setZ(); else clearZ();
				break;
			}
			case 103: // ACPX
			{
				tmp = getMemory(x) + getRq(arg0) + C();
				if (D() != 0)
				{
					if (tmp >= 10)
					{
						setMemory(x, (tmp - 10) & 0xF);
						setC();
					}
					else
					{
						setMemory(x, tmp);
						clearC();
					}
				}
				else
				{
					setMemory(x, tmp & 0xF);
					if ((tmp >> 4) != 0) setC(); else clearC();
				}
				if (getMemory(x) == 0) setZ(); else clearZ();
				x = ((x + 1) & 0xFF) | (((x >> 8) & 0xF) << 8);
				break;
			}
			case 104: // ACPY
			{
				tmp = getMemory(y) + getRq(arg0) + C();
				if (D() != 0)
				{
					if (tmp >= 10)
					{
						setMemory(y, (tmp - 10) & 0xF);
						setC();
					}
					else
					{
						setMemory(y, tmp);
						clearC();
					}
				}
				else
				{
					setMemory(y, tmp & 0xF);
					if ((tmp >> 4) != 0) setC(); else clearC();
				}
				if (getMemory(y) == 0) setZ(); else clearZ();
				y = ((y + 1) & 0xFF) | (((y >> 8) & 0xF) << 8);
				break;
			}
			case 105: // SCPX
			{
				tmp = (getMemory(x) - getRq(arg0) - C()) & 0xFF;
				if (D() != 0)
				{
					if ((tmp >> 4) != 0) setMemory(x, (tmp - 6) & 0xF);
					else setMemory(x, tmp);
				}
				else
				{
					setMemory(x, tmp & 0xF);
				}
				if ((tmp >> 4) != 0) setC(); else clearC();
				if (getMemory(x) == 0) setZ(); else clearZ();
				x = ((x + 1) & 0xFF) | (((x >> 8) & 0xF) << 8);
				break;
			}
			case 106: // SCPY
			{
				tmp = (getMemory(y) - getRq(arg0) - C()) & 0xFF;
				if (D() != 0)
				{
					if ((tmp >> 4) != 0) setMemory(y, (tmp - 6) & 0xF);
					else setMemory(y, tmp);
				}
				else
				{
					setMemory(y, tmp & 0xF);
				}
				if ((tmp >> 4) != 0) setC(); else clearC();
				if (getMemory(y) == 0) setZ(); else clearZ();
				y = ((y + 1) & 0xFF) | (((y >> 8) & 0xF) << 8);
				break;
			}
			case 107: // NOT
				setRq(arg0, ~getRq(arg0) & 0xF);
				if (getRq(arg0) == 0) setZ(); else clearZ();
				break;
		}
	}

	// --- Init ---
	public void init(int[] program, Hal hal, long tsFreq)
	{
		this.program = program;
		this.hal = hal;
		this.tsFreq = tsFreq;
		inputStates[0] = 0xF;
		inputStates[1] = 0xF;
		reset();
	}

	// --- Reset ---
	public void reset()
	{
		pc = 0x100; // bank 0, page 1, step 0
		np = 0x01;  // page 1
		a = 0;
		b = 0;
		x = 0;
		y = 0;
		sp = 0;
		flags = 0;
		Arrays.fill(memory, 0);
		memory[0xF54] = 0xF; // R40-R43 output port
		memory[0xF71] = 0x8; // LCD control
		memory[0xF41] = 0xF; // K00-K03 input relation (active high)
		cpuFrequency = OSC1_FREQUENCY;
		cpuHalted = false;
		callDepth = 0;
		previousCycles = 0;
		tickCounter = 0;
		scaledCycleAccumulator = 0;
		clkTimer2hzTs = 0;
		clkTimer4hzTs = 0;
		clkTimer8hzTs = 0;
		clkTimer16hzTs = 0;
		clkTimer32hzTs = 0;
		clkTimer64hzTs = 0;
		clkTimer128hzTs = 0;
		clkTimer256hzTs = 0;
		progTimerTs = 0;
		progTimerEnabled = false;
		progTimerData = 0;
		progTimerRld = 0;
		for (int i = 0; i < 6; i++)
		{
			interrupts[i].triggered = false;
		}
		if (hal != null)
		{
			refTs = hal.getTimestamp();
		}
	}

	// --- Refresh hardware (after state restore) ---
	public void refreshHw()
	{
		// Re-apply display memory
		for (int n = MEM_DISPLAY1_ADDR; n < MEM_DISPLAY1_ADDR + MEM_DISPLAY1_SIZE; n++)
		{
			setLcd(n - MEM_DISPLAY1_ADDR, memory[n]);
		}
		for (int n = MEM_DISPLAY2_ADDR; n < MEM_DISPLAY2_ADDR + MEM_DISPLAY2_SIZE; n++)
		{
			setLcd(n - MEM_DISPLAY1_ADDR, memory[n]);
		}
		// Re-apply buzzer
		if (memory[0xF74] != 0)
		{
			setBuzzerFreq(memory[0xF74] & 0x7);
		}
		hal.playFrequency((memory[0xF54] & 0x8) == 0);
	}

	// --- Speed control ---
	public void setSpeedRatio(int ratio)
	{
		this.speedRatio = ratio;
	}

	public int getSpeedRatio()
	{
		return speedRatio;
	}

	// --- State accessors for save/restore ---
	public int getPc()
	{
		return pc;
	}

	public void setPc(int pc)
	{
		this.pc = pc & 0x1FFF;
	}

	public int getA()
	{
		return a;
	}

	public void setA(int a)
	{
		this.a = a & 0xF;
	}

	public int getB()
	{
		return b;
	}

	public void setB(int b)
	{
		this.b = b & 0xF;
	}

	public int getX()
	{
		return x;
	}

	public void setX(int x)
	{
		this.x = x & 0xFFF;
	}

	public int getY()
	{
		return y;
	}

	public void setY(int y)
	{
		this.y = y & 0xFFF;
	}

	public int getNp()
	{
		return np;
	}

	public void setNp(int np)
	{
		this.np = np & 0x1F;
	}

	public int getSp()
	{
		return sp;
	}

	public void setSp(int sp)
	{
		this.sp = sp & 0xFF;
	}

	public int getFlags()
	{
		return flags;
	}

	public void setFlags(int flags)
	{
		this.flags = flags & 0xF;
	}

	public int[] getMemoryArray()
	{
		return memory;
	}

	public boolean isHalted()
	{
		return cpuHalted;
	}

	public void setHalted(boolean halted)
	{
		this.cpuHalted = halted;
	}

	public int getCallDepth()
	{
		return callDepth;
	}

	public long getTickCounter()
	{
		return tickCounter;
	}

	public void setTickCounter(long tickCounter)
	{
		this.tickCounter = tickCounter;
	}

	/**
	 * Serialize the full CPU state into an int array for persistence.
	 */
	public int[] saveState()
	{
		// Layout: registers(8) + timers(11) + interrupt state(18) + flags(3) + memory(4096) = 4136
		int[] state = new int[43 + MEMORY_SIZE];
		int i = 0;
		state[i++] = pc;
		state[i++] = x;
		state[i++] = y;
		state[i++] = a;
		state[i++] = b;
		state[i++] = np;
		state[i++] = sp;
		state[i++] = flags;
		// Tick counter split into two ints
		state[i++] = (int) (tickCounter & 0xFFFFFFFFL);
		state[i++] = (int) (tickCounter >>> 32);
		// Timer offsets relative to tick counter
		state[i++] = (int) (tickCounter - clkTimer2hzTs);
		state[i++] = (int) (tickCounter - clkTimer4hzTs);
		state[i++] = (int) (tickCounter - clkTimer8hzTs);
		state[i++] = (int) (tickCounter - clkTimer16hzTs);
		state[i++] = (int) (tickCounter - clkTimer32hzTs);
		state[i++] = (int) (tickCounter - clkTimer64hzTs);
		state[i++] = (int) (tickCounter - clkTimer128hzTs);
		state[i++] = (int) (tickCounter - clkTimer256hzTs);
		state[i++] = (int) (tickCounter - progTimerTs);
		// Interrupt state
		for (int s = 0; s < 6; s++)
		{
			state[i++] = interrupts[s].factorFlagReg;
			state[i++] = interrupts[s].maskReg;
			state[i++] = interrupts[s].triggered ? 1 : 0;
		}
		// Misc flags
		state[i++] = progTimerEnabled ? 1 : 0;
		state[i++] = progTimerData;
		state[i++] = progTimerRld;
		state[i++] = cpuHalted ? 1 : 0;
		state[i++] = callDepth;
		state[i++] = cpuFrequency;
		// Memory
		System.arraycopy(memory, 0, state, i, MEMORY_SIZE);
		return state;
	}

	/**
	 * Restore CPU state from a previously saved int array.
	 */
	public void loadState(int[] state)
	{
		if (state == null || state.length < 43 + MEMORY_SIZE)
		{
			return;
		}
		int i = 0;
		pc = state[i++];
		x = state[i++];
		y = state[i++];
		a = state[i++];
		b = state[i++];
		np = state[i++];
		sp = state[i++];
		flags = state[i++];
		tickCounter = (state[i++] & 0xFFFFFFFFL) | ((long) state[i++] << 32);
		// Restore timer timestamps from offsets
		clkTimer2hzTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		clkTimer4hzTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		clkTimer8hzTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		clkTimer16hzTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		clkTimer32hzTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		clkTimer64hzTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		clkTimer128hzTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		clkTimer256hzTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		progTimerTs = tickCounter - (state[i++] & 0xFFFFFFFFL);
		for (int s = 0; s < 6; s++)
		{
			interrupts[s].factorFlagReg = state[i++];
			interrupts[s].maskReg = state[i++];
			interrupts[s].triggered = state[i++] != 0;
		}
		progTimerEnabled = state[i++] != 0;
		progTimerData = state[i++];
		progTimerRld = state[i++];
		cpuHalted = state[i++] != 0;
		callDepth = state[i++];
		cpuFrequency = state[i++];
		System.arraycopy(state, i, memory, 0, MEMORY_SIZE);
		scaledCycleAccumulator = 0;
		previousCycles = 0;
		if (hal != null)
		{
			refTs = hal.getTimestamp();
		}
	}
}
