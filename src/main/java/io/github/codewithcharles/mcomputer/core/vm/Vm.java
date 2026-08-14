package io.github.codewithcharles.mcomputer.core.vm;

/**
 * A Lua VM, seen from a layer that may not name LuaJ. Also the seam
 * {@code MachineTest} drives a whole boot sequence through.
 */
public interface Vm {

    /**
     * Compiles a chunk and holds it, ready to run.
     *
     * <p>Separate from {@link #run()} because of threads: a script that does not
     * compile has to stop the machine from starting at all, and at that moment
     * the Lua thread does not exist. So this runs on the server thread, inside
     * {@code Machine.start()}, and its failure is synchronous.
     *
     * @param chunk     the source, as bytes - a Lua source file is not text
     * @param chunkName what error messages call it, e.g. {@code boot.lua}
     * @throws VmException if the chunk does not compile
     */
    void load(byte[] chunk, String chunkName);

    /**
     * Runs the loaded chunk to completion on the calling thread. This is the
     * half that belongs to the Lua thread; nothing here resumes.
     *
     * @throws IllegalStateException if no chunk was loaded
     * @throws VmException if the script raises an error or exhausts its budget
     */
    void run();
}
