import org.grove.vault.core.*;
import java.util.*;
public class DedupeTest {
    static Card card(String t, String user, String pw, String site) {
        Card c = new Card(); c.title = t;
        c.fields.add(new Field("Login", Field.LOGIN, user));
        if (pw != null) c.fields.add(new Field("Password", Field.PASSWORD, pw));
        c.fields.add(new Field("Website", Field.WEBSITE, site));
        return c;
    }
    public static void main(String[] a) throws Exception {
        Vault v = new Vault();
        Card g1 = card("Google", "harith@gmail.com", "pw1", "https://accounts.google.com"); g1.notes = "main";
        Card g2 = card("YouTube", "Harith@gmail.com ", "pw1", "https://youtube.com"); g2.favorite = true; g2.labelIds.add("L1"); g2.notes = "yt note";
        Card g3 = card("Gmail", "harith@gmail.com", "pw1", "https://mail.google.com");
        Card other = card("Bank", "harith@gmail.com", "pw2", "https://bank.com");
        Card pk = card("google.com", "harith@gmail.com", null, "https://google.com");
        Passkey p = Passkey.generate("google.com", "harith@gmail.com", "Harith", new byte[]{1,2,3});
        pk.fields.add(p.toField());
        v.cards.addAll(Arrays.asList(g1, g2, g3, other, pk));
        List<List<Card>> gs = Dedupe.groups(v);
        check(gs.size() == 1, "one group");
        check(gs.get(0).size() == 4 && gs.get(0).get(0) == g2, "group has 3 logins + passkey card, favourite kept");
        Card keep = Dedupe.merge(gs.get(0));
        int sites = 0; for (Field f : keep.fields) if (Field.WEBSITE.equals(f.type)) sites++;
        check(sites == 4, "websites combined: " + sites);
        check(keep.passkeys().size() == 1, "passkey moved");
        check(keep.notes.contains("main") && keep.notes.contains("yt note"), "notes combined");
        check(g1.deleted && g3.deleted && pk.deleted && !keep.deleted && !other.deleted, "others in trash only");
        check(Dedupe.groups(v).isEmpty(), "nothing left to combine");
        System.out.println("Dedupe: all checks passed");
    }
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
}
