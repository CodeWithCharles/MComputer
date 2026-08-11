package io.github.codewithcharles.mcomputer.minecraft.block.entity;

import io.github.codewithcharles.mcomputer.MComputer;
import io.github.codewithcharles.mcomputer.core.machine.Machine;
import io.github.codewithcharles.mcomputer.core.vm.VmException;
import io.github.codewithcharles.mcomputer.luaj.LuaJVm;
import io.github.codewithcharles.mcomputer.minecraft.block.ComputerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.nio.charset.StandardCharsets;

public class ComputerBlockEntity extends BlockEntity {

    /**
     * TODO: A guess, to be tuned when something actually submits.
     * tickRunsAtMostMaxTasksPerTick proves the
     * number reaches drain(), so tuning it will do something.
     */
    private static final int MAX_TASKS_PER_TICK = 16;

    /** TODO: a guess, like MAX_TASKS_PER_TICK. Tune when a real script exists. */
    private static final int INSTRUCTION_BUDGET = 5_000_000;

    private static final byte[] BOOT_SCRIPT = """
            print('MComputer booting')
            print('the instruction budget is armed')
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * <b>Only the server's copy of this block entity ever uses it.</b> A block
     * entity is created on both sides - the client builds one for rendering -
     * and a Machine running there would be a second computer nobody can see,
     * with its own thread and its own queue. Every caller below is guarded by
     * level.isClientSide, and that guard is the invariant, not a precaution.
     */
    private final Machine machine = new Machine(
            MAX_TASKS_PER_TICK,
            // Decoding here is legitimate and nowhere else: a log line IS text.
            // The VM keeps bytes right up to this point, which is why a script
            // printing binary corrupts the log entry and nothing else.
            () -> new LuaJVm(
                    line -> MComputer.LOGGER.info("[computer] {}",
                            new String(line, StandardCharsets.UTF_8)),
                    INSTRUCTION_BUDGET),
            BOOT_SCRIPT,
            "boot.lua");

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(MComputerBlockEntities.COMPUTER, pos, state);
    }

    public void toggle() {
        if (machine.isRunning()) {
            machine.stop();
        } else {
            startQuietly();
        }
        syncState();
    }

    public void serverTick() {
        boolean wasRunning = machine.isRunning();
        machine.tick();
        if (machine.isRunning() != wasRunning) {
            syncState();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        machine.stop();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("running", machine.isRunning());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        if (input.getBooleanOr("running", false)) {
            startQuietly();
        } else {
            machine.stop();
        }
    }

    /**
     * The one place the block's visible state is written. It exists because a
     * machine can now turn itself off - a script ends, or fails - so toggle() is
     * no longer the only moment the state changes. Before the Lua thread existed,
     * inlining this in toggle() was correct; it silently stopped being so.
     */
    private void syncState() {
        MComputer.LOGGER.info("computer at {} is now {}",
                getBlockPos(), machine.isRunning() ? "on" : "off");
        setChanged();
        if (getLevel() != null) {
            getLevel().setBlock(getBlockPos(),
                    getBlockState().setValue(ComputerBlock.LIT, machine.isRunning()),
                    Block.UPDATE_ALL);
        }
    }

    /**
     * A boot script that does not compile leaves the computer off. The failure
     * has already been written to the machine's output channel - the server log
     * today, the screen at milestone 3 - so there is nothing to add here, and
     * letting it escape would fail a world load.
     */
    private void startQuietly() {
        try {
            machine.start();
        } catch (VmException e) {
            // already reported on the output channel; the computer stays off
        }
    }
}