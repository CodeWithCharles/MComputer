package io.github.codewithcharles.mcomputer.minecraft.block.entity;

import io.github.codewithcharles.mcomputer.MComputer;
import io.github.codewithcharles.mcomputer.core.machine.Machine;
import io.github.codewithcharles.mcomputer.minecraft.block.ComputerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class ComputerBlockEntity extends BlockEntity {

    /**
     * TODO: A guess, to be tuned when something actually submits.
     * tickRunsAtMostMaxTasksPerTick proves the
     * number reaches drain(), so tuning it will do something.
     */
    private static final int MAX_TASKS_PER_TICK = 16;

    /**
     * <b>Only the server's copy of this block entity ever uses it.</b> A block
     * entity is created on both sides - the client builds one for rendering -
     * and a Machine running there would be a second computer nobody can see,
     * with its own thread and its own queue. Every caller below is guarded by
     * level.isClientSide, and that guard is the invariant, not a precaution.
     */
    private final Machine machine = new Machine(MAX_TASKS_PER_TICK);

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(MComputerBlockEntities.COMPUTER, pos, state);
    }

    public void toggle() {
        if (machine.isRunning()) {
            machine.stop();
        } else {
            machine.start();
        }
        MComputer.LOGGER.info("computer at {} is now {}",
                getBlockPos(), machine.isRunning() ? "on" : "off");
        setChanged();
        if (getLevel() != null) {
            getLevel().setBlock(getBlockPos(),
                    getBlockState().setValue(ComputerBlock.LIT, machine.isRunning()),
                    Block.UPDATE_ALL);
        }
    }

    public void serverTick() {
        machine.tick();
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
            machine.start();
        } else {
            machine.stop();
        }
    }
}