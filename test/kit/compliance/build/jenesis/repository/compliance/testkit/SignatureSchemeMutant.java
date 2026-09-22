package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * One deliberately broken substitution for the scheme a {@link SignatureSchemeContract.Property} is about - the
 * falsification half of the scheme kit. A mutant wraps the real verifier and removes one behaviour; the check whose
 * property that behaviour is must then fail, or the check is not measuring it.
 */
public enum SignatureSchemeMutant {

    /** The scheme's name: the provider answers the next constant along, so a lookup by scheme finds a verifier for
     *  evidence it cannot read. */
    A_SCHEME_MISNAMED("the provider's own scheme name") {
        @Override
        SignatureScheme substitute(SignatureScheme real) {
            return new Decorated(real) {
                @Override
                public ArtifactSignatures.Scheme scheme() {
                    ArtifactSignatures.Scheme[] all = ArtifactSignatures.Scheme.values();
                    return all[(real.scheme().ordinal() + 1) % all.length];
                }
            };
        }
    },

    /** The reader's refusal: bytes that are not of the scheme are answered with a reading that verifies nothing,
     *  so a garbled sidecar becomes a verdict instead of unreadable material. */
    A_READER_THAT_RECOGNISES_ANYTHING("the reader's refusal of material that is not of its scheme") {
        @Override
        SignatureScheme substitute(SignatureScheme real) {
            return new Decorated(real) {
                @Override
                public Optional<Reading> read(ArtifactSignatures.Evidence evidence) {
                    try {
                        Optional<Reading> read = real.read(evidence);
                        if (read.isPresent()) {
                            return read;
                        }
                    } catch (IOException unreadable) {
                        // read as nothing, below
                    }
                    return Optional.of(new Reading() {
                        @Override
                        public boolean heldBy(byte[] material) {
                            return false;
                        }

                        @Override
                        public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now) {
                            return new Verification(Result.INVALID, new SignerIdentity(real.material(), "nobody"),
                                    null, 0, null, null, null, SignatureQuality.unassessed(), null);
                        }
                    });
                }
            };
        }
    },

    /** The holder probe: every material holds every signer, so the first trust source speaks for all of them. */
    A_HOLDER_THAT_CLAIMS_EVERY_MATERIAL("the probe that picks the trust source by the signer its material holds") {
        @Override
        SignatureScheme substitute(SignatureScheme real) {
            return new Decorated(real) {
                @Override
                public Optional<Reading> read(ArtifactSignatures.Evidence evidence) throws IOException {
                    return real.read(evidence).map(reading -> new Reading() {
                        @Override
                        public boolean heldBy(byte[] material) {
                            return true;
                        }

                        @Override
                        public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                                throws IOException, GeneralSecurityException {
                            return reading.verify(covered, material, now);
                        }
                    });
                }
            };
        }
    },

    /** The verification itself: whatever the bytes and whatever the material, the answer is VALID. */
    A_VERIFIER_THAT_ANSWERS_VALID("the check of the signature over the covered bytes against the material") {
        @Override
        SignatureScheme substitute(SignatureScheme real) {
            return rewriting(real, verified -> new SignatureScheme.Verification(SignatureScheme.Result.VALID,
                    verified.signer(), verified.keyAlgorithm(), verified.keyBits(), verified.hashAlgorithm(),
                    verified.created(), verified.signerExpiry(), verified.quality(), null));
        }
    },

    /** The signer's vocabulary: the identity is named in a scheme no pin can be written for. */
    A_SIGNER_OF_ANOTHER_SCHEME("the naming of the signer in the material's own identity scheme") {
        @Override
        SignatureScheme substitute(SignatureScheme real) {
            return rewriting(real, verified -> new SignatureScheme.Verification(verified.result(),
                    new SignerIdentity("nobody", verified.signer().value()), verified.keyAlgorithm(),
                    verified.keyBits(), verified.hashAlgorithm(), verified.created(), verified.signerExpiry(),
                    verified.quality(), verified.reason()));
        }
    },

    /** The grade: a verified signature is reported unassessed, with no key and no digest. */
    A_GRADE_OF_NOTHING("the grade and the key facts of what verified") {
        @Override
        SignatureScheme substitute(SignatureScheme real) {
            return rewriting(real, verified -> new SignatureScheme.Verification(verified.result(),
                    verified.signer(), null, 0, null, verified.created(), verified.signerExpiry(),
                    SignatureQuality.unassessed(), verified.reason()));
        }
    },

    /** The bound: an exception the covered stream raises is caught and answered INVALID. */
    A_VERIFIER_THAT_SWALLOWS_THE_BOUND("the propagation of an IOException the covered stream raises") {
        @Override
        SignatureScheme substitute(SignatureScheme real) {
            return new Decorated(real) {
                @Override
                public Optional<Reading> read(ArtifactSignatures.Evidence evidence) throws IOException {
                    return real.read(evidence).map(reading -> new Reading() {
                        @Override
                        public boolean heldBy(byte[] material) throws IOException {
                            return reading.heldBy(material);
                        }

                        @Override
                        public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                                throws GeneralSecurityException {
                            try {
                                return reading.verify(covered, material, now);
                            } catch (IOException swallowed) {
                                return new Verification(Result.INVALID, new SignerIdentity(real.material(), "nobody"),
                                        null, 0, null, null, null, SignatureQuality.unassessed(),
                                        swallowed.getMessage());
                            }
                        }
                    });
                }
            };
        }
    };

    private final String removes;

    SignatureSchemeMutant(String removes) {
        this.removes = removes;
    }

    /** What the mutation takes away, for the failure message. */
    public String removes() {
        return removes;
    }

    /** The fixture, answering the mutated scheme in place of the discovered one. */
    public SignatureSchemeFixture decorate(SignatureSchemeFixture fixture) {
        SignatureScheme mutated = substitute(fixture.scheme());
        return new SignatureSchemeFixture() {
            @Override
            public String schemeClass() {
                return fixture.schemeClass();
            }

            @Override
            public SignatureScheme scheme() {
                return mutated;
            }

            @Override
            public ArtifactSignatures.Scheme declared() {
                return fixture.declared();
            }

            @Override
            public byte[] covered() {
                return fixture.covered();
            }

            @Override
            public byte[] tampered() {
                return fixture.tampered();
            }

            @Override
            public ArtifactSignatures.Evidence evidence() {
                return fixture.evidence();
            }

            @Override
            public ArtifactSignatures.Evidence unrecognisable() {
                return fixture.unrecognisable();
            }

            @Override
            public byte[] material() {
                return fixture.material();
            }

            @Override
            public byte[] foreign() {
                return fixture.foreign();
            }

            @Override
            public SignerIdentity signer() {
                return fixture.signer();
            }
        };
    }

    abstract SignatureScheme substitute(SignatureScheme real);

    /** A scheme whose readings verify as the real one does and then rewrite the verification. */
    private static SignatureScheme rewriting(SignatureScheme real,
                                             UnaryOperator<SignatureScheme.Verification> rewrite) {
        return new Decorated(real) {
            @Override
            public Optional<Reading> read(ArtifactSignatures.Evidence evidence) throws IOException {
                return real.read(evidence).map(reading -> new Reading() {
                    @Override
                    public boolean heldBy(byte[] material) throws IOException {
                        return reading.heldBy(material);
                    }

                    @Override
                    public Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                            throws IOException, GeneralSecurityException {
                        return rewrite.apply(reading.verify(covered, material, now));
                    }
                });
            }
        };
    }

    /** The identity decoration every mutant starts from. */
    private abstract static class Decorated implements SignatureScheme {

        final SignatureScheme real;

        Decorated(SignatureScheme real) {
            this.real = real;
        }

        @Override
        public ArtifactSignatures.Scheme scheme() {
            return real.scheme();
        }

        @Override
        public String material() {
            return real.material();
        }

        @Override
        public Optional<Reading> read(ArtifactSignatures.Evidence evidence) throws IOException {
            return real.read(evidence);
        }

        @Override
        public String unrecognised() {
            return real.unrecognised();
        }
    }
}
