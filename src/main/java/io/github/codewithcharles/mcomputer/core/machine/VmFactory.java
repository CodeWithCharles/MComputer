package io.github.codewithcharles.mcomputer.core.machine;

import io.github.codewithcharles.mcomputer.core.vm.Vm;

/**
 * Produces the VM of one run, and cannot produce one without that run's
 * plumbing.
 *
 * <p>The parameter is the guarantee: a VM built here holds its queues as final
 * fields from its first line, so there is no window in which a loaded VM is not
 * yet connected, and no extra method whose contract is an ordering.
 *
 * <p>It lives beside {@code Machine} rather than beside {@code Vm} because its
 * parameter names {@link Signal}. In {@code core/vm} it would make that package
 * depend on this one, which already depends on it.
 */
@FunctionalInterface
public interface VmFactory {

    Vm create(MachineAccess access, InstructionBudget budget);
}
