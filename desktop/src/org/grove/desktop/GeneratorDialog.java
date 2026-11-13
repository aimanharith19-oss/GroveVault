package org.grove.desktop;

import org.grove.vault.core.PasswordTools;

import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Password / passphrase generator dialog. */
public final class GeneratorDialog {
    private GeneratorDialog() {}

    public static void show(Component parent, Consumer<String> use) {
        PasswordTools.Options o = new PasswordTools.Options();
        o.length = Ux.getInt("gen_len", 16);
        o.passphrase = "1".equals(Ux.get("gen_phrase", "0"));
        o.words = Ux.getInt("gen_words", 5);
        JTextField out = new JTextField(32);
        out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
        out.setEditable(false);
        JLabel strength = new JLabel(" ");
        JRadioButton random = new JRadioButton("Random", !o.passphrase), phrase = new JRadioButton("Passphrase", o.passphrase);
        ButtonGroup g = new ButtonGroup();
        g.add(random);
        g.add(phrase);
        JSpinner len = new JSpinner(new SpinnerNumberModel(o.length, 6, 128, 1));
        JSpinner words = new JSpinner(new SpinnerNumberModel(o.words, 3, 12, 1));
        JCheckBox up = new JCheckBox("A–Z", true), low = new JCheckBox("a–z", true), dig = new JCheckBox("0–9", true),
                sym = new JCheckBox("!@#…", true), amb = new JCheckBox("Avoid look-alikes", true);
        Runnable gen = () -> {
            o.passphrase = phrase.isSelected();
            o.length = (Integer) len.getValue();
            o.words = (Integer) words.getValue();
            o.upper = up.isSelected();
            o.lower = low.isSelected();
            o.digits = dig.isSelected();
            o.symbols = sym.isSelected();
            o.avoidAmbiguous = amb.isSelected();
            String p = PasswordTools.generate(o);
            out.setText(p);
            int s = PasswordTools.score(p);
            strength.setText(PasswordTools.SCORE_NAMES[s] + " · ~" + (int) PasswordTools.entropy(p) + " bits");
            strength.setForeground(s <= 1 ? Ux.DANGER : s == 2 ? Ux.WARN : Ux.ACCENT);
            Ux.put("gen_len", o.length);
            Ux.put("gen_words", o.words);
            Ux.put("gen_phrase", o.passphrase ? "1" : "0");
        };
        for (AbstractButton b : new AbstractButton[]{random, phrase, up, low, dig, sym, amb}) b.addActionListener(e -> gen.run());
        len.addChangeListener(e -> gen.run());
        words.addChangeListener(e -> gen.run());
        JPanel mode = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        mode.add(random);
        mode.add(phrase);
        JPanel sets = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (JCheckBox c : new JCheckBox[]{up, low, dig, sym, amb}) sets.add(c);
        JPanel p = Ux.form(new String[]{"", "", "Type", "Length", "Characters", "Words"}, new JComponent[]{out, strength, mode, len, sets, words});
        gen.run();
        String[] opts = use == null ? new String[]{"Copy", "New", "Close"} : new String[]{"Use", "New", "Cancel"};
        while (true) {
            int r = JOptionPane.showOptionDialog(parent, p, "Password generator", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
            if (r == 1) { gen.run(); continue; }
            if (r == 0) {
                if (use == null) Ux.copy(parent, "Password", out.getText());
                else use.accept(out.getText());
            }
            return;
        }
    }
}
