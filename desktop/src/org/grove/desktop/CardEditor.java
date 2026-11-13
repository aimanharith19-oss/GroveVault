package org.grove.desktop;

import org.grove.vault.core.*;
import org.grove.vault.core.Label;

import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** Modal editor for a card or template. Edits a copy; returns true if the user saved. */
public final class CardEditor {
    private CardEditor() {}

    public static boolean edit(Frame owner, Vault vault, Card work, boolean isNew) {
        JDialog d = new JDialog(owner, work.template ? (isNew ? "New template" : "Edit template") : (isNew ? "New card" : "Edit card"), true);
        boolean[] saved = {false};
        JTextField title = new JTextField(work.title, 30);
        JComboBox<String> color = new JComboBox<>(new String[]{"Green", "Blue", "Purple", "Red", "Orange", "Teal", "Brown", "Indigo", "Lime", "Cyan", "Pink", "Grey"});
        color.setSelectedIndex(Math.abs(work.color) % 12);
        JCheckBox fav = new JCheckBox("Favourite", work.favorite);

        // fields table
        List<Field> fields = new ArrayList<>();
        for (Field f : work.fields) fields.add(f.copy());
        String[] typeNames = Field.TYPE_NAMES;
        AbstractTableModel model = new AbstractTableModel() {
            public int getRowCount() { return fields.size(); }
            public int getColumnCount() { return 3; }
            public String getColumnName(int c) { return new String[]{"Name", "Type", "Value"}[c]; }
            public Object getValueAt(int r, int c) {
                Field f = fields.get(r);
                if (c == 0) return f.name;
                if (c == 1) return Field.typeName(f.type);
                if (Field.PASSKEY.equals(f.type)) {
                    Passkey p = Passkey.decode(f.value);
                    return p == null ? "(passkey)" : "🔐 passkey for " + p.rpId;
                }
                return f.value;
            }
            public boolean isCellEditable(int r, int c) {
                return !Field.PASSKEY.equals(fields.get(r).type) || c == 0;
            }
            public void setValueAt(Object v, int r, int c) {
                Field f = fields.get(r);
                if (c == 0) f.name = String.valueOf(v);
                else if (c == 1) {
                    for (int i = 0; i < typeNames.length; i++) if (typeNames[i].equals(v)) f.type = Field.TYPES[i];
                } else f.value = String.valueOf(v);
                fireTableRowsUpdated(r, r);
            }
        };
        JTable table = new JTable(model);
        table.setRowHeight(26);
        table.setSurrendersFocusOnKeystroke(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(typeNames)));
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(320);
        // mask secrets in the table
        table.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            protected void setValue(Object v) {
                setText(v == null ? "" : v.toString());
            }
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                Field f = fields.get(r);
                if (f.isSecret() && !Field.PASSKEY.equals(f.type) && !f.value.isEmpty()) setText("••••••••  (double-click to edit)");
                return comp;
            }
        });
        JScrollPane ts = new JScrollPane(table);
        ts.setPreferredSize(new Dimension(620, 200));

        JPanel fb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fb.add(Ux.button("Add field", e -> {
            stop(table);
            Object t = JOptionPane.showInputDialog(d, "Field type:", "Add field", JOptionPane.PLAIN_MESSAGE, null, typeNames, typeNames[0]);
            if (t == null) return;
            int i = java.util.Arrays.asList(typeNames).indexOf(t);
            String n = JOptionPane.showInputDialog(d, "Field name:", typeNames[i]);
            if (n == null) return;
            fields.add(new Field(n.trim().isEmpty() ? typeNames[i] : n.trim(), Field.TYPES[i], ""));
            model.fireTableDataChanged();
        }));
        fb.add(Ux.button("Remove", e -> {
            stop(table);
            int r = table.getSelectedRow();
            if (r < 0) return;
            if (Field.PASSKEY.equals(fields.get(r).type) && !Ux.confirm(d, "Remove this passkey? You won't be able to sign in with it any more.")) return;
            fields.remove(r);
            model.fireTableDataChanged();
        }));
        fb.add(Ux.button("Up", e -> move(table, fields, model, -1)));
        fb.add(Ux.button("Down", e -> move(table, fields, model, 1)));
        fb.add(Ux.button("Generate password", e -> {
            stop(table);
            int r = table.getSelectedRow();
            if (r < 0 || !Field.PASSWORD.equals(fields.get(r).type)) {
                for (int i = 0; i < fields.size(); i++) if (Field.PASSWORD.equals(fields.get(i).type)) { r = i; break; }
            }
            if (r < 0 || r >= fields.size() || !Field.PASSWORD.equals(fields.get(r).type)) {
                Ux.info(d, "Add a Password field first.");
                return;
            }
            int row = r;
            GeneratorDialog.show(d, p -> { fields.get(row).value = p; model.fireTableDataChanged(); });
        }));
        fb.add(Ux.button("Show value", e -> {
            int r = table.getSelectedRow();
            if (r >= 0 && !Field.PASSKEY.equals(fields.get(r).type)) {
                JTextField tf = new JTextField(fields.get(r).value, 30);
                tf.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
                JOptionPane.showMessageDialog(d, tf, fields.get(r).name, JOptionPane.PLAIN_MESSAGE);
            }
        }));

        JTextArea notes = new JTextArea(work.notes, 5, 40);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);

        // labels
        vault.sortLabels();
        DefaultListModel<String> lm = new DefaultListModel<>();
        for (Label l : vault.labels) lm.addElement(l.name);
        JList<String> labels = new JList<>(lm);
        labels.setVisibleRowCount(4);
        List<Integer> sel = new ArrayList<>();
        for (int i = 0; i < vault.labels.size(); i++) if (work.labelIds.contains(vault.labels.get(i).id)) sel.add(i);
        labels.setSelectedIndices(sel.stream().mapToInt(Integer::intValue).toArray());
        labels.setToolTipText("Ctrl+click to choose several labels");
        JButton newLabel = Ux.button("New label…", e -> {
            String n = JOptionPane.showInputDialog(d, "Label name:");
            if (n == null || n.trim().isEmpty()) return;
            int[] keep = labels.getSelectedIndices();
            List<String> keepNames = new ArrayList<>();
            for (int k : keep) keepNames.add(lm.get(k));
            keepNames.add(n.trim());
            vault.labelNamed(n.trim());
            vault.sortLabels();
            lm.clear();
            for (Label l : vault.labels) lm.addElement(l.name);
            List<Integer> s2 = new ArrayList<>();
            for (int i = 0; i < lm.size(); i++) if (keepNames.contains(lm.get(i))) s2.add(i);
            labels.setSelectedIndices(s2.stream().mapToInt(Integer::intValue).toArray());
        });
        JPanel lp = new JPanel(new BorderLayout(4, 0));
        lp.add(new JScrollPane(labels), BorderLayout.CENTER);
        lp.add(newLabel, BorderLayout.EAST);

        // attachments
        List<Attachment> atts = new ArrayList<>(work.attachments);
        DefaultListModel<String> am = new DefaultListModel<>();
        Runnable refreshAtt = () -> { am.clear(); for (Attachment a : atts) am.addElement(a.name + "  (" + (a.data.length / 1024 + 1) + " KB)"); };
        refreshAtt.run();
        JList<String> attList = new JList<>(am);
        attList.setVisibleRowCount(3);
        JPanel ab = new JPanel(new GridLayout(2, 1, 0, 4));
        ab.add(Ux.button("Attach…", e -> {
            JFileChooser ch = new JFileChooser();
            if (ch.showOpenDialog(d) != JFileChooser.APPROVE_OPTION) return;
            File f = ch.getSelectedFile();
            if (f.length() > 5 * 1024 * 1024) { Ux.error(d, "Attachments can be up to 5 MB."); return; }
            try {
                String mime = Files.probeContentType(f.toPath());
                atts.add(new Attachment(f.getName(), mime == null ? org.grove.vault.core.kdbx.KeePassMapper.mimeFor(f.getName()) : mime, Files.readAllBytes(f.toPath())));
                refreshAtt.run();
            } catch (Exception ex) { Ux.error(d, ex.getMessage()); }
        }));
        ab.add(Ux.button("Remove", e -> { int i = attList.getSelectedIndex(); if (i >= 0) { atts.remove(i); refreshAtt.run(); } }));
        JPanel ap = new JPanel(new BorderLayout(4, 0));
        ap.add(new JScrollPane(attList), BorderLayout.CENTER);
        ap.add(ab, BorderLayout.EAST);

        JPanel head = Ux.form(new String[]{work.template ? "Template name" : "Title", "Colour", ""}, new JComponent[]{title, color, fav});
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(head);
        center.add(section("Fields"));
        center.add(ts);
        center.add(fb);
        center.add(section("Notes"));
        center.add(new JScrollPane(notes));
        center.add(section("Labels"));
        center.add(lp);
        if (!work.template) {
            center.add(section("Attachments"));
            center.add(ap);
        }
        for (Component c : center.getComponents()) ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        buttons.add(save);
        buttons.add(cancel);
        save.addActionListener(e -> {
            stop(table);
            work.title = title.getText().trim();
            if (work.title.isEmpty()) {
                if (work.template) { Ux.error(d, "Give the template a name."); return; }
                Field w = null;
                for (Field f : fields) if (Field.WEBSITE.equals(f.type) && !f.value.isEmpty()) w = f;
                work.title = w != null ? Card.domainOf(w.value) : "Untitled";
            }
            work.color = color.getSelectedIndex();
            work.favorite = fav.isSelected();
            work.fields.clear();
            work.fields.addAll(fields);
            work.notes = notes.getText();
            work.labelIds.clear();
            for (int i : labels.getSelectedIndices()) work.labelIds.add(vault.labels.get(i).id);
            work.attachments.clear();
            work.attachments.addAll(atts);
            saved[0] = true;
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        d.getRootPane().setDefaultButton(save);
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        root.add(new JScrollPane(center) {{ setBorder(null); }}, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setSize(Math.max(d.getWidth(), 720), Math.min(Math.max(d.getHeight(), 560), 800));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return saved[0];
    }

    private static JComponent section(String s) {
        JLabel l = new JLabel(s.toUpperCase());
        l.setForeground(Ux.ACCENT);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        l.setBorder(BorderFactory.createEmptyBorder(12, 2, 4, 0));
        return l;
    }

    private static void stop(JTable t) {
        if (t.isEditing()) t.getCellEditor().stopCellEditing();
    }

    private static void move(JTable t, List<Field> fields, AbstractTableModel m, int dir) {
        stop(t);
        int r = t.getSelectedRow();
        int n = r + dir;
        if (r < 0 || n < 0 || n >= fields.size()) return;
        Field f = fields.remove(r);
        fields.add(n, f);
        m.fireTableDataChanged();
        t.setRowSelectionInterval(n, n);
    }
}
