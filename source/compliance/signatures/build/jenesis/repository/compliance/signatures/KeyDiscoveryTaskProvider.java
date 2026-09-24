package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/** Schedules {@link KeyDiscoveryTask} with the sources {@code signature-key-discovery} names - the two public
 *  keyservers by key id, asked in the order named, the Web Key Directory by maintainer e-mail, GitHub by
 *  maintainer login; nothing when the dial is written empty, which is the pass's off switch. */
public final class KeyDiscoveryTaskProvider implements MaintenanceTaskProvider {

    public KeyDiscoveryTaskProvider() {
    }

    @Override
    public String name() {
        return KeyDiscoveryTask.NAME;
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!KeyDiscoveryTask.enabled(config)) {
            return Optional.empty();
        }
        List<KeyDiscoveryTask.Fetcher> keyservers = new ArrayList<>();
        if (KeyDiscoveryTask.names(config, KeyDiscoveryTask.KEYSERVER_UBUNTU)) {
            keyservers.add(KeyDiscoveryTask.hkp(config.apply(KeyDiscoveryTask.UBUNTU_URL)));
        }
        if (KeyDiscoveryTask.names(config, KeyDiscoveryTask.KEYS_OPENPGP_ORG)) {
            keyservers.add(KeyDiscoveryTask.vks(config.apply(KeyDiscoveryTask.URL)));
        }
        return Optional.of(new KeyDiscoveryTask(KeyDiscoveryTask.INTERVAL.resolve(config),
                new KeyDiscoveryTask.Sources(
                        keyservers.isEmpty() ? null : KeyDiscoveryTask.first(keyservers),
                        KeyDiscoveryTask.names(config, KeyDiscoveryTask.WKD) ? KeyDiscoveryTask.wkd(null) : null,
                        KeyDiscoveryTask.names(config, KeyDiscoveryTask.GITHUB) ? KeyDiscoveryTask.github(null) : null)));
    }
}
