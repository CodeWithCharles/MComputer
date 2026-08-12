package io.github.codewithcharles.mcomputer.minecraft.block.entity;

import com.mojang.serialization.Codec;
import io.github.codewithcharles.mcomputer.MComputer;
import io.github.codewithcharles.mcomputer.core.machine.Machine;
import io.github.codewithcharles.mcomputer.core.screen.ScreenBuffer;
import io.github.codewithcharles.mcomputer.core.screen.ScreenOutput;
import io.github.codewithcharles.mcomputer.core.vm.VmException;
import io.github.codewithcharles.mcomputer.luaj.LuaJVm;
import io.github.codewithcharles.mcomputer.minecraft.block.ComputerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
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

//    private static final byte[] BOOT_SCRIPT = """
//            print('MComputer booting')
//            print('the instruction budget is armed')
//            """.getBytes(StandardCharsets.UTF_8);
//    private static final byte[] BOOT_SCRIPT = """
//            print('MComputer booting')
//            error('boom')
//            """.getBytes(StandardCharsets.UTF_8);
//    private static final byte[] BOOT_SCRIPT = """
//            print('MComputer booting'
//            """.getBytes(StandardCharsets.UTF_8);
//    private static final byte[] BOOT_SCRIPT = """
//            print('MComputer booting')
//            while true do end
//            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] BOOT_SCRIPT = """
            print('MComputer booting')
            print('\\218\\196\\196\\196\\196\\196\\196\\191')
            print('\\179 caf\\130 \\179')
            print('\\192\\196\\196\\196\\196\\196\\196\\217')
            """.getBytes(StandardCharsets.UTF_8);

    private static final int SCREEN_WIDTH = 80;
    private static final int SCREEN_HEIGHT = 25;

    /**
     * TODO: a guess, like MAX_TASKS_PER_TICK. It has two jobs - how many lines
     * may wait for the next drain, and how much work one tick may do - so it
     * must be at least SCREEN_HEIGHT, or a burst that fills the screen would
     * lose lines the buffer had room for.
     */
    private static final int MAX_PENDING_LINES = 64;

    /**
     * The screen's storage. Server-side only, like the Machine: the client copy
     * of this block entity holds one nobody ever writes into.
     */
    private final ScreenBuffer screen = new ScreenBuffer(SCREEN_WIDTH, SCREEN_HEIGHT);

    /**
     * What the VM writes into. It stands between the threads that produce output
     * and the buffer, which assumes a single one.
     */
    private final ScreenOutput screenOutput = new ScreenOutput(screen, MAX_PENDING_LINES);

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
            () -> new LuaJVm(screenOutput, INSTRUCTION_BUDGET),
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
        // Unconditional, and isRunning() must never guard it: at the moment
        // tick() notices the Lua thread is dead and stops the machine, the
        // script's last prints and its failure line are still in the queue.
        // They would be lost, and the failure line is the one the player needs.
        if (screenOutput.drain() > 0) {
            sendScreen();
        }
    }

    /**
     * The grid the terminal draws. The client's copy is kept current by this
     * block entity's own update packet; nothing outside this class writes to it.
     */
    public ScreenBuffer screen() {
        return screen;
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

        // Applied on both sides on purpose: on the client because that is what
        // the sync is for, and on the server where the key is simply absent and
        // the Optional is empty. The divergence is carried by the Optional, not
        // by a branch of our own.
        input.read("screen", Codec.BYTE_BUFFER).ifPresent(incoming -> {
            byte[] cells = new byte[incoming.remaining()];
            incoming.get(cells);
            screen.restore(cells, input.getIntOr("screen_row", 0));
        });

        Level level = getLevel();
        if (level != null && level.isClientSide()) {
            return;
        }

        if (input.getBooleanOr("running", false)) {
            startQuietly();
        } else {
            machine.stop();
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveCustomOnly(registries);
        // Written on the CompoundTag rather than through saveAdditional, and
        // that is the whole trick: the disk path never sees these two keys, so
        // "the buffer does not survive a world reload" costs no code at all.
        tag.putByteArray("screen", screen.snapshot());
        tag.putInt("screen_row", screen.writePosition());
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
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
     * Pushes the screen to every client tracking this block.
     *
     * <p>No setChanged() here, unlike syncState() and unlike vanilla's
     * markUpdated(): the screen is synchronised and <b>not saved</b>, so it
     * wants the network half and not the disk half. Marking the chunk dirty for
     * a grid that never reaches the disk would be paid on every printed line.
     *
     * <p>The if at the call site is not an optimisation either. Without it this
     * would run twenty times a second for every computer in the world, over a
     * grid nobody touched - the same if syncState() had to learn at milestone 2,
     * for the same reason.
     */
    private void sendScreen() {
        Level level = getLevel();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(),
                    Block.UPDATE_ALL);
        }
    }

    /**
     * A boot script that does not compile leaves the computer off. The failure
     * has already been written to the machine's output channel on the machine's
     * output channel, which is the screen so there is nothing to add here, and
     * letting it escape would fail a world load.
     *
     * A boot starts on a clean screen; an extinction does not clear it, so the
     * error that killed a script stays readable until the next boot.
     *
     * <p>The only write to the buffer outside the drain, and it is legal because
     * this runs on the server thread - from toggle(), and from loadAdditional(),
     * measured Server-thread-only at milestone 1. Anything reaching the buffer
     * from any other thread goes through screenOutput.
     *
     * <p>A boot script that does not compile leaves the computer off. The failure
     * has already been written to the machine's output channel, which is now the
     * screen, so there is nothing to add here - and letting it escape would fail
     * a world load.
     */
    private void startQuietly() {
        screen.clear();
        try {
            machine.start();
        } catch (VmException e) {
            // already reported on the output channel; the computer stays off
        }
    }
}