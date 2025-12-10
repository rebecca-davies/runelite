package net.runelite.client.plugins.pathmaker;


import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.ImageIcon;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;

@Slf4j
public class PathPanel extends JPanel
{
    private static final Border NAME_BOTTOM_BORDER = new CompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
            BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR));

    private static final ImageIcon EXPAND_ICON;
    private static final ImageIcon COLLAPSE_ICON;
    private static final ImageIcon LOOP_ON_ICON;
    private static final ImageIcon LOOP_OFF_ICON;
    private static final ImageIcon EYE_OPEN_ICON;
    private static final ImageIcon EYE_CLOSED_ICON;

    private final PathmakerPlugin plugin;
    private final PathmakerPath path;

    private final JPanel pathContainer = new JPanel();
    private final JButton label = new JButton();

    private final int ICON_WIDTH = 18;

    //private boolean panelExpanded = true;
    private final JButton expandToggle;
    private final JButton visibilityToggle;

    private final BufferedImage brushImage = ImageUtil.loadImageResource(PathmakerPlugin.class, "brush.png");
    private final BufferedImage crossImage = ImageUtil.loadImageResource(PathmakerPlugin.class, "cross.png");

    //private final int MAX_LABEL_LENGTH = 15;

    static
    {
        final int MAX_LABEL_LENGTH = 15;
        BufferedImage upArrowImage = ImageUtil.loadImageResource(PathmakerPlugin.class, "up_arrow.png");
        COLLAPSE_ICON = new ImageIcon(upArrowImage);
        EXPAND_ICON= new ImageIcon(ImageUtil.rotateImage(upArrowImage, Math.PI));
        LOOP_ON_ICON = new ImageIcon(ImageUtil.loadImageResource(PathmakerPlugin.class, "loop_on.png"));
        LOOP_OFF_ICON = new ImageIcon(ImageUtil.loadImageResource(PathmakerPlugin.class, "loop_off.png"));
        EYE_OPEN_ICON = new ImageIcon(ImageUtil.loadImageResource(PathmakerPlugin.class, "eye_open.png"));
        EYE_CLOSED_ICON = new ImageIcon(ImageUtil.loadImageResource(PathmakerPlugin.class, "eye_closed.png"));
    }

    PathPanel(PathmakerPlugin plugin, String pathLabel)
    {
        this.plugin = plugin;
        this.path = plugin.getStoredPaths().get(pathLabel);


        JPanel labelPanel = new JPanel(new BorderLayout());
        labelPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        label.setText(pathLabel);
        label.setForeground(Color.WHITE); //path.color);
		label.setToolTipText(pathLabel);
        label.setPreferredSize(new Dimension(140, 20)); // Client.PANEL_WIDTH = 225. (18x4 buttons, 5 margin
        labelPanel.add(label, BorderLayout.CENTER);

        expandToggle = new JButton(path.panelExpanded ? COLLAPSE_ICON : EXPAND_ICON);
        expandToggle.setPreferredSize(new Dimension(ICON_WIDTH, 0));
        expandToggle.setToolTipText((path.panelExpanded ? "Expand" : "Collapse") + " path");
        expandToggle.addActionListener(actionEvent ->
        {
            toggleCollapsed();
        });

        visibilityToggle = new JButton(path.hidden ? EYE_CLOSED_ICON : EYE_OPEN_ICON);
        visibilityToggle.setPreferredSize(new Dimension(ICON_WIDTH, 0));
        visibilityToggle.setToolTipText((path.hidden ? "Show" : "Hide") + " path");
        visibilityToggle.addActionListener(actionEvent ->
        {
            toggleVisibility();
        });

        JButton colorPickerButton = new JButton();
        colorPickerButton.setPreferredSize(new Dimension(ICON_WIDTH, 0));
        colorPickerButton.setIcon(new ImageIcon(ImageUtil.recolorImage(brushImage, path.color)));
        colorPickerButton.setToolTipText("Choose path color");
        colorPickerButton.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent mouseEvent)
            {
                RuneliteColorPicker colorPicker = getColorPicker(path.color == null ? plugin.getDefaultPathColor() : path.color, colorPickerButton);
                colorPicker.setOnColorChange(newColor ->
                {
                    path.color = newColor;
                    label.setForeground(newColor);
                    colorPickerButton.setIcon(new ImageIcon(ImageUtil.recolorImage(brushImage, newColor)));
                });
                colorPicker.setVisible(true);
            }
        });

        // Add button panel on the left
        JPanel leftActionPanel = new JPanel(new BorderLayout());
        leftActionPanel.add(expandToggle, BorderLayout.WEST);
        leftActionPanel.add(visibilityToggle, BorderLayout.CENTER);
        leftActionPanel.add(colorPickerButton, BorderLayout.EAST);
        labelPanel.add(leftActionPanel, BorderLayout.WEST);

        JButton deletePathButton = new JButton();
        deletePathButton.setIcon(new ImageIcon(ImageUtil.recolorImage(crossImage, Color.RED)));
        deletePathButton.setToolTipText("Delete path");
        deletePathButton.setPreferredSize(new Dimension(ICON_WIDTH, 0));
        String warningMsg = "Are you sure you want to permanently delete path: " + label.getText() + "?";
        deletePathButton.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent mouseEvent) {
                int confirm = JOptionPane.showConfirmDialog(PathPanel.this,
                        warningMsg,
                        "Warning", JOptionPane.OK_CANCEL_OPTION);

                if (confirm == 0)
                {
                    plugin.removePath(label.getText());
                    plugin.rebuildPanel(true);
                }
            }
        });

        // Add loop button
        JButton loopButton = new JButton();
        loopButton.setIcon(path.loopPath ? LOOP_ON_ICON : LOOP_OFF_ICON);
        loopButton.setPreferredSize(new Dimension(ICON_WIDTH, 0));
        loopButton.setToolTipText((path.loopPath ? "disable" :  "enable") + " path loop");
        loopButton.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent mouseEvent)
            {
                path.loopPath = !path.loopPath;
                loopButton.setToolTipText((path.loopPath ? "disable" :  "enable") + " path loop");
                loopButton.setIcon(path.loopPath ? LOOP_ON_ICON : LOOP_OFF_ICON);
            }
        });

        // Add button panel to the right
        JPanel rightActionPanel = new JPanel(new BorderLayout());
        rightActionPanel.add(loopButton, BorderLayout.WEST);
        rightActionPanel.add(deletePathButton, BorderLayout.EAST);
        labelPanel.add(rightActionPanel, BorderLayout.EAST);

        pathContainer.setLayout(new BoxLayout(pathContainer, BoxLayout.Y_AXIS));
        pathContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        pathContainer.add(labelPanel, BorderLayout.CENTER);

        int pathSize = path.getSize();
        for (PathPoint point : path.getDrawOrder(null))
        {
            JPanel pointContainer = new JPanel(new BorderLayout());
            pointContainer.setBorder(new EmptyBorder(5, 0, 5, 0));
            pointContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);

            //
            FlatTextField pointLabel = new FlatTextField();
            String label = "";// = "Point";
            label = (point.getLabel() != null && !point.getLabel().isEmpty()) ? point.getLabel() : label;
            pointLabel.setText(label);
            pointLabel.setForeground(Color.WHITE);
            pointLabel.setBackground(Color.DARK_GRAY);
            pointLabel.setPreferredSize(new Dimension(150, 20));
            pointLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            ((AbstractDocument) pointLabel.getTextField().getDocument()).setDocumentFilter(new MaxLengthFilter(plugin.MAX_POINT_LABEL_LENGTH));
            pointLabel.getDocument().addDocumentListener(new DocumentListener()
            {
                public void insertUpdate(DocumentEvent e) {point.setLabel(pointLabel.getText());}
                public void removeUpdate(DocumentEvent e) {point.setLabel(pointLabel.getText());}
                public void changedUpdate(DocumentEvent e) {}
            });

            pointContainer.add(pointLabel, BorderLayout.WEST);

            // Add spinner box for optionally assigning a new point index.
            JSpinner indexSpinner = new JSpinner(new SpinnerNumberModel(point.getDrawIndex() + 1, 1, pathSize, -1));
            indexSpinner.setToolTipText("point index");
            indexSpinner.addChangeListener(ce ->
            {
                plugin.getStoredPaths().get(pathLabel).setNewIndex(point, (Integer) indexSpinner.getValue() - 1);
                plugin.rebuildPanel(true);
            });
            pointContainer.add(indexSpinner, BorderLayout.CENTER);

            JButton deletePathPointButton = new JButton();
            deletePathPointButton.setIcon(new ImageIcon(crossImage));
            deletePathPointButton.setToolTipText("Delete point");
            deletePathPointButton.setPreferredSize(new Dimension(ICON_WIDTH, 0));
            deletePathPointButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent mouseEvent) {
                    plugin.removePoint(getPathLabel().getText(), point);
                    // Rebuilding in removePoint (because of the in-game shift+click dropdown menu)
                }
            });
            pointContainer.add(deletePathPointButton, BorderLayout.EAST);

            pointContainer.setVisible(path.panelExpanded);
            pathContainer.add(pointContainer);
        }
        add(pathContainer);
    }

    private void toggleCollapsed()
    {
        path.panelExpanded = !path.panelExpanded;
        for (int i = 1; i < pathContainer.getComponentCount(); i++)
        {
            pathContainer.getComponent(i).setVisible(path.panelExpanded);
        }

        expandToggle.setIcon(path.panelExpanded ? COLLAPSE_ICON : EXPAND_ICON);
        expandToggle.setToolTipText((path.panelExpanded ? "Collapse" : "Expand") + " path");
    }

    private void toggleVisibility()
    {
        path.hidden = !path.hidden;

        visibilityToggle.setIcon(path.hidden ? EYE_CLOSED_ICON : EYE_OPEN_ICON);
        visibilityToggle.setToolTipText((path.hidden ? "Show" : "Hide") + " path");
    }

    void setPathLabel(String label)
    {
        this.label.setText(label);
    }

    JButton getPathLabel()
    {
        return label;
    }

    private RuneliteColorPicker getColorPicker(Color colour, Component relativeTo)
    {
        RuneliteColorPicker colorPicker = plugin.getColorPickerManager().create(
                SwingUtilities.windowForComponent(this),
                colour,
                label.getText() + " path color",
                false);
        colorPicker.setLocationRelativeTo(relativeTo);
        return colorPicker;
    }
}