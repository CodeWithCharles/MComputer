package io.github.codewithcharles.mcomputer.core.vm;

public interface Vm {

    /**
     * Compiles a chunk and holds it, ready to run.
     *
     * <p>Separate from {@link #run()} for one reason, and it is about threads
     * rather than about ergonomics: a script that does not compile must stop the
     * machine from starting at all, and at that moment the Lua thread does not
     * exist yet. So this runs on the <b>server thread</b>, inside
     * {@code Machine.start()}, and its failure is synchronous - the machine is
     * simply never turned on.
     *
     * <p>The cost is accepted and named: compiling happens inside a tick. It is
     * microseconds for any plausible script, and a cap on source size is already
     * carried as an open question rather than guessed at now.
     *
     * @param chunk     the source, as bytes - a Lua source file is not text
     * @param chunkName what error messages call it, e.g. {@code boot.lua}
     * @throws VmException if the chunk does not compile
     */
    void load(byte[] chunk, String chunkName);

    /**
     * Runs the loaded chunk to completion on the calling thread.
     *
     * <p>This is the half that belongs to the Lua thread. It returns when the
     * script is done; nothing here resumes.
     *
     * @throws IllegalStateException if no chunk was loaded - running an empty VM
     *         is a caller bug, not a state to branch on
     * @throws VmException if the script raises an error or exhausts its budget
     */
    void run();
}
