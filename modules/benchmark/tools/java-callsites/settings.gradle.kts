// Deliberately its own build, not a module of the main one: this is a ground-truth extractor for
// benchmark questions, run by hand a few times, and its heavyweight symbol-solving dependency has
// no business on the benchmark module's compile classpath.
rootProject.name = "java-callsites"
