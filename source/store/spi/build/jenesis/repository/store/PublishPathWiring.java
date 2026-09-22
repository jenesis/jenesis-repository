package build.jenesis.repository.store;

/**
 * A boot-time arrangement that must be in place before anything publishes - declared as a type so that whatever
 * publishes during start-up can wait for it without naming what it is waiting for.
 *
 * <p>The case it exists for: a deployment may install something that arms the publish path at boot - a screen
 * supplier, an interceptor's backing configuration - and a boot action that publishes (seeding a fresh
 * repository, replaying a migration) must not run before it. Expressing that as a dependency on the arming
 * bean's own type couples the publisher to whichever module happens to provide it, and makes the publisher
 * unbuildable in a deployment that provides none.
 *
 * <p>So the publisher asks for every bean of this type instead. It receives an empty collection where nothing
 * arms the publish path, and a populated one where something does; either way the container has finished
 * building them before the publisher is built, which is the whole of what the publisher needs. The value is
 * never read - only the ordering matters - so nothing here declares a method.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Ordering.</b> An implementation's bean must be fully constructed before any boot action that
 *       publishes. That is what a container gives by constructing an injected dependency first; nothing else
 *       here enforces it.</li>
 *   <li><b>Absence sentinel.</b> None installed is an empty collection, never an error: a deployment that arms
 *       nothing on the publish path is the ordinary case.</li>
 *   <li><b>Lifecycle / ownership.</b> The implementing bean is owned by whichever composition declares it,
 *       including its shutdown. Implementing this says nothing about lifetime beyond "built before the
 *       publisher"; an implementation that must be torn down says so its own way.</li>
 *   <li><b>Read purity.</b> Nothing calls anything on it, so an implementation may not rely on being asked.</li>
 * </ol>
 */
public interface PublishPathWiring {
}
