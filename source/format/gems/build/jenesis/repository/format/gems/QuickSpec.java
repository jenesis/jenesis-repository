package build.jenesis.repository.format.gems;

import module java.base;

/**
 * The legacy RubyGems quick spec, kept apart from the modern compact index that {@link RubyGemsFormat} otherwise
 * speaks. {@code bundle install} resolves entirely over the compact index ({@code /versions}, {@code /info}); plain
 * {@code gem install}, however, still fetches each gem's full {@code Gem::Specification} from the pre-compact-index
 * {@code /quick/Marshal.4.8/<name>-<version>.gemspec.rz} endpoint - a zlib-deflated Ruby Marshal of the spec, loaded
 * by the client through {@code Gem::SafeMarshal}. This class reproduces that Marshal from Java (via {@link
 * RubyMarshal}) so the endpoint can be served without a Ruby runtime; the format only needs to hand it the parsed
 * coordinate, its runtime dependencies and its Ruby constraint. Kept deliberately in one place so the legacy surface
 * is easy to see - and to drop, once no consumer needs bare {@code gem install}.
 */
final class QuickSpec {

    private QuickSpec() {
    }

    /** The deflated quick spec for {@code gem install} - {@code Marshal.dump(Gem::Specification)}, zlib-compressed. */
    static byte[] deflated(RubyGemsFormat.Spec spec) {
        RubyMarshal marshal = new RubyMarshal();
        marshal.header();
        marshal.userDefined("Gem::Specification", specification(spec));
        return deflate(marshal.bytes());
    }

    /**
     * The gem's {@code Gem::Specification#_dump} payload: a self-contained Marshal stream of the 19-field attribute
     * array the current spec version marshals, with only the fields {@code gem install} needs populated (name,
     * version, the two requirements, platform, runtime dependencies, metadata) and the rest left empty or nil. The
     * date is nil - the client defaults it on load - so no {@code Time} has to be marshaled.
     */
    private static byte[] specification(RubyGemsFormat.Spec spec) {
        RubyMarshal m = new RubyMarshal();
        m.header();
        m.array(19);
        m.string("3.6.0");                     // rubygems_version
        m.integer(4);                           // specification_version
        m.string(spec.name());                 // name
        version(m, spec.version());            // version
        m.nil();                                // date
        m.string("");                          // summary
        requirement(m, spec.ruby());           // required_ruby_version
        requirement(m, List.of());             // required_rubygems_version (>= 0)
        m.string("ruby");                      // original_platform
        m.array(spec.deps().size());           // dependencies
        for (RubyGemsFormat.Dependency dependency : spec.deps()) {
            dependency(m, dependency.name(), dependency.requirement());
        }
        m.string("");                          // rubyforge_project
        m.nil();                                // email
        m.array(0);                             // authors
        m.nil();                                // description
        m.nil();                                // homepage
        m.bool(true);                           // has_rdoc
        m.string("ruby");                      // new_platform
        m.nil();                                // licenses
        m.emptyHash();                          // metadata
        return m.bytes();
    }

    /** {@code Gem::Version#marshal_dump} is {@code [version_string]}. */
    private static void version(RubyMarshal m, String value) {
        m.userMarshal("Gem::Version");
        m.array(1);
        m.string(value);
    }

    /** {@code Gem::Requirement#marshal_dump} is {@code [[[op, Gem::Version], ...]]}; an empty one is the {@code >= 0}
     *  default a bare requirement carries. */
    private static void requirement(RubyMarshal m, List<RubyGemsFormat.Constraint> constraints) {
        m.userMarshal("Gem::Requirement");
        m.array(1);
        List<RubyGemsFormat.Constraint> requirements = constraints.isEmpty()
                ? List.of(new RubyGemsFormat.Constraint(">=", "0")) : constraints;
        m.array(requirements.size());
        for (RubyGemsFormat.Constraint constraint : requirements) {
            m.array(2);
            m.string(constraint.op());
            version(m, constraint.version());
        }
    }

    /** {@code Gem::Dependency} marshals as a plain object; the client permits exactly these ivars. */
    private static void dependency(RubyMarshal m, String name, List<RubyGemsFormat.Constraint> requirement) {
        m.object("Gem::Dependency", 4);
        m.symbol("@name");
        m.string(name);
        m.symbol("@requirement");
        requirement(m, requirement);
        m.symbol("@type");
        m.symbol("runtime");
        m.symbol("@prerelease");
        m.bool(false);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
