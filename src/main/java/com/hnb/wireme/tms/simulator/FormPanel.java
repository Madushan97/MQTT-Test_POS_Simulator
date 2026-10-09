package com.hnb.wireme.tms.simulator;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** Label/field rows with section headings, top-aligned — the left-hand form of each tab. */
final class FormPanel extends JPanel {

    private int row;

    FormPanel() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }

    FormPanel section(String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        GridBagConstraints c = constraints(0, row++);
        c.gridwidth = 2;
        c.insets = new Insets(row == 1 ? 0 : 12, 0, 4, 0);
        add(l, c);
        return this;
    }

    FormPanel row(String label, JComponent field) {
        if (field instanceof JTextField t) {
            t.setColumns(12); // otherwise a long value (a DB host, a URL) sets the whole form's width
        } else if (field instanceof JComboBox<?> c) {
            c.setPreferredSize(new Dimension(150, c.getPreferredSize().height)); // same, for the longest item
        }
        GridBagConstraints lc = constraints(0, row);
        lc.weightx = 0;
        lc.insets = new Insets(2, 0, 2, 8);
        add(new JLabel(label), lc);
        GridBagConstraints fc = constraints(1, row++);
        fc.weightx = 1;
        add(field, fc);
        return this;
    }

    FormPanel full(JComponent c) {
        GridBagConstraints gc = constraints(0, row++);
        gc.gridwidth = 2;
        gc.weightx = 1;
        add(c, gc);
        return this;
    }

    /** Small wrapped help text; fixed width so it never widens the form. */
    FormPanel note(String html) {
        return full(new JLabel("<html><body style='width:300px'><small>" + html + "</small></body></html>"));
    }

    /** Pushes the rows to the top. */
    FormPanel end() {
        GridBagConstraints c = constraints(0, row++);
        c.weighty = 1;
        add(new JPanel(), c);
        return this;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 0);
        return c;
    }
}
