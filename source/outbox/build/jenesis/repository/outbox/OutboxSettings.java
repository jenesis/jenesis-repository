package build.jenesis.repository.outbox;

import module java.base;

import build.jenesis.repository.maintenance.RetentionSetting;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * The parked backlog's retention, declared once for every outbox. A permanently gone target parks an entry per delivery
 * for ever, so how long a dead delivery is kept is a deployment's dial - one dial for every user, since two keys for
 * one behaviour would drift. Age is the default bound, and a count cap gives a hard ceiling regardless of rate; both
 * are read by {@link Outbox#prunePark}.
 */
public final class OutboxSettings implements SettingsContributor {

    /** How long a parked entry is kept before a drain reclaims it; thirty days by default. */
    public static final RetentionSetting PARKED_RETENTION =
            RetentionSetting.of("outbox-parked-retention", "P30D");

    /** The parked backlog's count bound's key; unset is off, since the age bound is the default one. */
    public static final String PARKED_CAP = "outbox-parked-cap";

    /** The parked backlog's count bound as configured: unset or unparseable is off. */
    public static int parkedCap(UnaryOperator<String> config) {
        String setting = config.apply(PARKED_CAP);
        try {
            return setting == null || setting.isBlank() ? 0 : Integer.parseInt(setting.trim());
        } catch (NumberFormatException unparseable) {
            return 0;
        }
    }

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(PARKED_RETENTION.key(), "Outboxes", "Parked entry retention",
                        "How long a terminally-failed (parked) outbox delivery, such as a webhook's, is kept "
                                + "before its drain reclaims it. A parked entry stays visible on its status surface "
                                + "and is recoverable through its retry endpoint, so this is how long a dead delivery "
                                + "is worth keeping rather than how long it is retried. Blank keeps them forever, "
                                + "which lets a target that is permanently gone accumulate one entry per delivery. "
                                + "One dial for every outbox: the mechanism is shared, so its retention is too.",
                        Setting.Kind.DURATION, PARKED_RETENTION.fallbackText(), false).advanced(),
                new Setting(PARKED_CAP, "Outboxes", "Parked entry cap",
                        "A hard ceiling on a parked backlog: everything beyond the newest N is reclaimed whatever "
                                + "its age. Off by default, since the retention above already bounds it by time; set "
                                + "this where a burst of failures could outgrow the space before the age bound "
                                + "reaches it.",
                        Setting.Kind.INTEGER, "0", false).advanced());
    }
}
