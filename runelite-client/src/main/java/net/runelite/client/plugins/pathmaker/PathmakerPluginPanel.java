package net.runelite.client.plugins.pathmaker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.ImageUtil;

import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AbstractDocument;

@Slf4j
public class PathmakerPluginPanel extends PluginPanel
{
    private static final ImageIcon IMPORT_ICON;
    private static final ImageIcon EXPORT_ICON;

    private final PluginErrorPanel noPathPanel = new PluginErrorPanel();
    private final JPanel pathView = new JPanel();

    Client client;
    PathmakerPlugin plugin;

    FlatTextField activePath;
    final int MAX_PATH_NAME_LENGTH = 20; // Based on ÆÆÆÆÆÆÆÆÆ (9)

    static
    {
        IMPORT_ICON = new ImageIcon(ImageUtil.loadImageResource(PathmakerPlugin.class, "import.png"));
        EXPORT_ICON = new ImageIcon(ImageUtil.loadImageResource(PathmakerPlugin.class, "export.png"));
    }

    // Making a pair inner class for import/export as path names aren't stored in pathmakerPaths
    public static class Pair<T, E>
    {
        @Getter
        private final T key;
        @Getter
        private final E value;

        Pair(T key, E value)
        {
            this.key = key;
            this.value = value;
        }
    }

    PathmakerPluginPanel(Client client, PathmakerPlugin plugin)
    {
        this.client = client;
	    this.plugin = plugin;

        // Define standard client panel layout
        setLayout(new BorderLayout());
        //setBorder(new EmptyBorder(10, 10, 10, 10));

        // Create title panel
        JPanel titlePanel = new JPanel();
        //titlePanel.setBorder(new EmptyBorder(1, 0, 10, 0));

        // Create label and add to title panel
        JLabel title = new JLabel();
        title.setText("Pathmaker");
        title.setPreferredSize(new Dimension(80,20)); //getGraphics().getFontMetrics().stringWidth(title.getText()) + 10
        title.setForeground(Color.WHITE);
        title.setToolTipText("by Fraph");
        titlePanel.add(title, BorderLayout.CENTER);

        // EXPORT / IMPORT
        JButton exportButton = new JButton();
        exportButton.setIcon(EXPORT_ICON);
        exportButton.setToolTipText("Export active path to clipboard");
        exportButton.setPreferredSize(new Dimension(18, 18));
        exportButton.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent mouseEvent)
            {
                if(!plugin.getStoredPaths().containsKey(activePath.getText())) {return;}

                //new Pair<String, PathmakerPath>(activePath.getText(), plugin.getStoredPaths().get(activePath.getText()));
				JsonObject exportPath = new JsonObject();
				exportPath.add(activePath.getText(), plugin.pathToJson(activePath.getText()));

                StringSelection json = new StringSelection(plugin.gson.toJson(exportPath));
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(json, null);
            }
        });

        JButton importButton = new JButton();
        importButton.setIcon(IMPORT_ICON);
        importButton.setToolTipText("Import path from clipboard");
        importButton.setPreferredSize(new Dimension(18, 18));
        importButton.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent mouseEvent)
            {
				String json = "";
				try
				{
					json = (String) getToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
				}
				catch (IllegalArgumentException | UnsupportedFlavorException | IllegalStateException |
					   NullPointerException | IOException ex)
				{
					log.debug("Clipboard is unavailable or has invalid content");
				}

				if (json.isEmpty())
				{
					return;
				}

				JsonObject element;
				try
				{
					element = new JsonParser().parse(json).getAsJsonObject();
				}
				catch (JsonParseException e)
				{
					log.debug("String was not a valid JSON: {}", e.getMessage());
					return;
				}

				// OLD
				// Pair<String, PathmakerPath> MUST be a JSON object
				// Returns if JSON is valid but not suitable for Pair<...>
//                if (!element.isJsonObject()) {return;}

//                Pair<String, PathmakerPath> loadedPath;
//                try{loadedPath = plugin.gson.fromJson(element, new TypeToken<Pair<String, PathmakerPath>>(){}.getType());}
//                catch (JsonSyntaxException e)
//                {
//                    log.debug("JSON is an object, but not in the correct Pair structure: {}", e.getMessage());
//                    return;
//                }

				String jsonPathName = element.keySet().iterator().next();

				JLabel centeredNameText = new JLabel("Path name", JLabel.CENTER);
				centeredNameText.setHorizontalTextPosition(SwingConstants.CENTER);
				//String pathName = JOptionPane.showInputDialog(importButton, centeredNameText, loadedPath.getKey());
				String inputPathName = JOptionPane.showInputDialog(importButton, centeredNameText, jsonPathName);

				// Return if the window was cancelled or closed
				if (inputPathName == null)
				{
					return;
				}

				// Show warning if imported path exists
				if (plugin.getStoredPaths().containsKey(inputPathName))
				{
					JLabel centeredWarningText = new JLabel("The path name " + inputPathName + " already exist.", JLabel.CENTER);
					JLabel centeredReplaceText = new JLabel("Replace it?", JLabel.CENTER);

					centeredWarningText.setHorizontalTextPosition(SwingConstants.CENTER);
					centeredReplaceText.setHorizontalTextPosition(SwingConstants.CENTER);

					JPanel centeredTextFrame = new JPanel(new GridLayout(0, 1));
					centeredTextFrame.setAlignmentX(Component.CENTER_ALIGNMENT);
					centeredTextFrame.add(centeredWarningText);
					centeredTextFrame.add(centeredReplaceText);

					int confirm = JOptionPane.showConfirmDialog(null,
						centeredTextFrame, "Warning", JOptionPane.YES_NO_OPTION);

					if (confirm == JOptionPane.YES_OPTION)// || confirm == JOptionPane.CLOSED_OPTION)
					{
						plugin.removePath(inputPathName);
						plugin.loadPathFromJson(element.get(jsonPathName).getAsJsonObject(), inputPathName);
						plugin.rebuildPanel(true);
						activePath.setText(inputPathName);
					}
				}
				else
				{
					plugin.loadPathFromJson(element.get(jsonPathName).getAsJsonObject(), inputPathName);
					plugin.rebuildPanel(true);
					activePath.setText(inputPathName);
				}
            }
        });

        JPanel rightActionTitlePanel = new JPanel();
        rightActionTitlePanel.add(importButton, BorderLayout.WEST);
        rightActionTitlePanel.add(exportButton, BorderLayout.EAST);
        titlePanel.add(rightActionTitlePanel, BorderLayout.EAST);

        // Create body panel and add titlePanel
        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.setBorder(new EmptyBorder(1, 0, 10, 0));
        northPanel.add(titlePanel, BorderLayout.NORTH);

        // Add link to config button
        // But how? Watchdogs implementation
        // https://github.com/adamk33n3r/runelite-watchdog/blob/master/src/main/java/com/adamk33n3r/runelite/watchdog/NotificationOverlay.java
//        JButton configButton = new JButton();
//        configButton.setIcon(new ImageIcon(ImageUtil.loadImageResource(PathmakerPlugin.class, "cross.png")));
//        configButton.setToolTipText("Config");
//        configButton.addChangeListener(ce ->
//        {
//            plugin.getEventBus().post(
//                    new OverlayMenuClicked(
//                    new OverlayMenuEntry(
//                    RUNELITE_OVERLAY_CONFIG,
//                            null,
//                            null),
//                            notificationOverlay)); <---- What in this is relevant? (link above)
//        });
//        northPanel.add(configButton);

        // Add Active Path text field
        JLabel activePathLabel = new JLabel("Active Path: ");
        activePathLabel.setPreferredSize(new Dimension(75, 20));
        activePathLabel.setForeground(Color.WHITE);
        northPanel.add(activePathLabel, BorderLayout.WEST);

        activePath = new FlatTextField();
        ((AbstractDocument) activePath.getDocument()).setDocumentFilter(new MaxLengthFilter(MAX_PATH_NAME_LENGTH));
        activePath.setText("unnamed");
        activePath.setForeground(Color.WHITE);
        activePath.setBackground(Color.DARK_GRAY);

        northPanel.add(activePath);

        // Add panel to client panel
        add(northPanel, BorderLayout.NORTH);

        // Configure path view panel
        pathView.setLayout(new BoxLayout(pathView, BoxLayout.Y_AXIS));

        // Configure PluginErrorPanel
        noPathPanel.setVisible(false);
        //noPathPanel.setPreferredSize(new Dimension(50, 30));
        noPathPanel.setContent("No stored paths", "Shift right-click a tile to add a path point.");

        // Add body panel
        JPanel centerPanel = new JPanel(new BorderLayout());
        //centerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH-5, 0));
        centerPanel.add(noPathPanel, BorderLayout.NORTH);
        centerPanel.add(pathView, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);

        rebuild();
    }

    void rebuild()
    {
        pathView.removeAll();

        // HashMap<String, PathmakerPath>
//        HashMap<String, PathmakerPath> paths = plugin.getStoredPaths();
        for (final String pathLabel : plugin.getStoredPaths().keySet())
        {
            // Create new path entry
            PathPanel pathEntry = new PathPanel(plugin, pathLabel);

            // Set as active path on label click
            pathEntry.getPathLabel().addActionListener(actionEvent ->
            {
                activePath.setText(pathEntry.getPathLabel().getText());
            });
            pathView.add(pathEntry, BorderLayout.CENTER);
        }

        boolean empty = pathView.getComponentCount() == 0;
        noPathPanel.setVisible(empty);

        repaint();
        revalidate();
    }
}
