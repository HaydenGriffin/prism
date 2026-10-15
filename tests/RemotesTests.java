package io.github.haydengriffin.prism;

import java.util.List;

/** Setup → Remotes (kinds, defaults, switches, the saved list), the box's name, and the monitoring entity id. */
public final class RemotesTests {
    private static int assertions;

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        kinds();
        defaults();
        choices();
        saved();
        limit();
        monitoringEntity();
        boxName();
        System.out.println(assertions + " Remotes assertions passed");
    }

    private static void kinds() {
        check(RemotePolicy.kind("SHIELD Remote", 5, false) == RemotePolicy.Kind.PHYSICAL, "a Bluetooth remote is physical");
        check(RemotePolicy.kind("Hdmi CEC remote", 3, false) == RemotePolicy.Kind.CEC, "CEC by name");
        check(RemotePolicy.kind("HDMI CEC", 3, true) == RemotePolicy.Kind.CEC, "CEC wins over virtual");
        check(RemotePolicy.kind("Virtual", -1, true) == RemotePolicy.Kind.VIRTUAL, "adb's virtual keyboard");
        check(RemotePolicy.kind("virtual-remote", 9, false) == RemotePolicy.Kind.VIRTUAL, "virtual by name");
        check(RemotePolicy.kind("Phone remote", 12, true) == RemotePolicy.Kind.VIRTUAL, "Android's virtual flag");
        check(RemotePolicy.kind("Keyboard", -1, false) == RemotePolicy.Kind.VIRTUAL, "no device id: virtual");
        check(RemotePolicy.kind(null, 4, false) == RemotePolicy.Kind.VIRTUAL, "no device: virtual");
    }

    private static void defaults() {
        check(RemotePolicy.controlsByDefault(RemotePolicy.Kind.PHYSICAL), "physical remotes control the LG by default");
        check(!RemotePolicy.controlsByDefault(RemotePolicy.Kind.CEC), "CEC never by default: it would loop back to the TV");
        check(!RemotePolicy.controlsByDefault(RemotePolicy.Kind.VIRTUAL), "virtual devices are opt-in");
        Remotes r = new Remotes();
        check(r.controls("unseen", RemotePolicy.Kind.PHYSICAL), "an unseen physical remote works before it is listed");
        check(!r.controls("unseen", RemotePolicy.Kind.CEC), "an unseen CEC device does not");
    }

    private static void choices() {
        Remotes r = new Remotes();
        check(r.seen("bt1", "SHIELD Remote", RemotePolicy.Kind.PHYSICAL), "a new device changes the list");
        check(!r.seen("bt1", "SHIELD Remote", RemotePolicy.Kind.PHYSICAL), "the same press again changes nothing");
        check(r.seen("bt1", "SHIELD Remote 2", RemotePolicy.Kind.PHYSICAL), "a rename is saved");
        r.seen("cec", "Hdmi CEC", RemotePolicy.Kind.CEC);
        r.seen("phone", "Virtual", RemotePolicy.Kind.VIRTUAL);
        r.choose("bt1", false);
        check(!r.controls("bt1", RemotePolicy.Kind.PHYSICAL), "a physical remote can be turned off");
        r.choose("phone", true);
        check(r.controls("phone", RemotePolicy.Kind.VIRTUAL), "a phone remote app can be turned on");
        r.choose("cec", true);
        check(!r.controls("cec", RemotePolicy.Kind.CEC), "CEC can't be turned on: it would loop back to the TV");
        check(r.list().get(1).chosen == null && !r.list().get(1).controls(), "choosing CEC stores nothing");
        check(!RemotePolicy.switchable(RemotePolicy.Kind.CEC) && RemotePolicy.switchable(RemotePolicy.Kind.VIRTUAL),
            "only CEC has no switch");
        r.seen("bt1", "SHIELD Remote 3", RemotePolicy.Kind.PHYSICAL);
        check(!r.controls("bt1", RemotePolicy.Kind.PHYSICAL), "a rename keeps the choice");
        r.choose("never-seen", true);
        check(r.list().size() == 3, "choosing an unknown device adds nothing");
        List<Remotes.Device> list = r.list();
        check(list.get(0).id.equals("bt1") && !list.get(0).controls(), "list keeps first-seen order and the switch");
        check(Remotes.id("", "Remote").equals("name:Remote") && Remotes.id(null, null).equals("name:unnamed"),
            "no descriptor: keyed by name");
        check(Remotes.id("abc123", "Remote").equals("abc123"), "the descriptor is the key when there is one");
    }

    private static void saved() {
        Remotes r = new Remotes();
        r.seen("bt1", "Remote \"A\"", RemotePolicy.Kind.PHYSICAL);
        r.seen("phone", "Virtual", RemotePolicy.Kind.VIRTUAL);
        r.choose("phone", true);
        Remotes back = Remotes.fromJson(r.toJson());
        check(back.list().size() == 2, "round trip keeps every device");
        check(back.list().get(0).name.equals("Remote \"A\"") && back.list().get(0).chosen == null,
            "names are escaped; no choice stays no choice");
        check(back.controls("phone", RemotePolicy.Kind.VIRTUAL), "choices survive a restart");
        Remotes cec = Remotes.fromJson("[{\"id\":\"c\",\"name\":\"Hdmi CEC\",\"kind\":\"CEC\",\"on\":true}]");
        check(!cec.controls("c", RemotePolicy.Kind.CEC) && cec.list().get(0).chosen == null, "a saved CEC switch is ignored");
        check(Remotes.fromJson("not json").list().isEmpty(), "an unreadable list starts empty");
        check(Remotes.fromJson("").list().isEmpty() && Remotes.fromJson(null).list().isEmpty(), "nothing saved yet");
        Remotes partial = Remotes.fromJson("[{\"id\":\"a\",\"name\":\"A\",\"kind\":\"PHYSICAL\"},{\"id\":\"b\",\"kind\":\"ROBOT\"},{\"name\":\"no id\",\"kind\":\"CEC\"}]");
        check(partial.list().size() == 1, "unreadable entries are skipped");
    }

    private static void limit() {
        Remotes r = new Remotes();
        for (int i = 0; i < Remotes.MAX + 5; i++) r.seen("d" + i, "Device " + i, RemotePolicy.Kind.VIRTUAL);
        check(r.list().size() == Remotes.MAX, "the list is capped");
        check(r.list().get(0).id.equals("d5"), "the oldest are forgotten first");
    }

    private static void boxName() {
        BoxName shield = BoxName.of("SHIELD", "SHIELD Android TV");
        check(shield.label().equals("SHIELD") && shield.the().equals("the SHIELD") && shield.input().equals("SHIELD input"),
            "the device name from Android's settings");
        check(BoxName.of("  ", "Chromecast").the().equals("the Chromecast"), "no device name: the model");
        check(BoxName.of(" Lounge box ", null).label().equals("Lounge box"), "trimmed");
        BoxName unknown = BoxName.of(null, "");
        check(unknown.label().equals("This box") && unknown.the().equals("this box") && unknown.input().equals("Box input"),
            "neither: this box");
        check(Status.of(true, true, true, new RemoteState(RemoteState.Mode.SHIELD, ""), shield).text.equals("On the SHIELD"),
            "the live line names the box");
        check(Status.of(true, true, true, new RemoteState(RemoteState.Mode.PAUSED, ""), unknown).text.equals("Back on this box"),
            "and falls back to this box");
        check(TvCue.text(shield).equals("Remote on the LG \u00b7 hold Back for the SHIELD"), "the on-TV cue names the box");
    }

    private static void monitoringEntity() {
        check("input_text.prism".equals(Heartbeat.entity(" Input_Text.Prism ")), "trimmed and lower-cased");
        check("input_text.tv_remote_2".equals(Heartbeat.entity("input_text.tv_remote_2")), "any input_text helper");
        check(Heartbeat.entity("sensor.prism") == null, "only input_text can be set");
        check(Heartbeat.entity("input_text.") == null && Heartbeat.entity("input_text.a b") == null, "malformed ids refused");
        check(Heartbeat.entity("") == null && Heartbeat.entity(null) == null, "empty is off");
    }
}
