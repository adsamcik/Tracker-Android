# Activity source assertion repair

V1 preserved. Static review found that the plain historical configuration class uses reference equality. Compare the actual exposed admission and original configuration values instead of requiring two independently decoded objects to have the same reference. Production unchanged. Three authored methods NOT_RUN; implementation only.
