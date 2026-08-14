package io.github.codewithcharles.mcomputer.minecraft.block.entity;

import com.mojang.serialization.Codec;
import io.github.codewithcharles.mcomputer.MComputer;
import io.github.codewithcharles.mcomputer.core.component.BoundaryLimits;
import io.github.codewithcharles.mcomputer.core.component.Component;
import io.github.codewithcharles.mcomputer.core.machine.Machine;
import io.github.codewithcharles.mcomputer.core.screen.Gpu;
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
import java.util.UUID;

public class ComputerBlockEntity extends BlockEntity {

    /** TODO: a guess. tickRunsAtMostMaxTasksPerTick proves it reaches drain(). */
    private static final int MAX_TASKS_PER_TICK = 16;

    /** OpenComputers' default and minimum. */
    private static final int SIGNAL_QUEUE_CAPACITY = 256;

    /** TODO: a guess, like MAX_TASKS_PER_TICK. */
    private static final int INSTRUCTION_BUDGET = 5_000_000;

    private static final byte[] BOOT_SCRIPT = """
            local gpu
            for address, kind in pairs(component.list()) do
                if kind == 'gpu' then gpu = address end
            end
            print('gpu at ' .. gpu)
            local width, height = component.invoke(gpu, 'getResolution')
            print('resolution ' .. width .. 'x' .. height)
            component.invoke(gpu, 'set', 1, 5, 'written by the gpu')
            print(done)
            """.getBytes(StandardCharsets.UTF_8);

    private static final int SCREEN_WIDTH = 80;
    private static final int SCREEN_HEIGHT = 25;

    /**
     * TODO: a guess. It has two jobs - how many lines may wait for the next
     * drain, and how much work one tick may do - so it must be at least
     * SCREEN_HEIGHT, or a burst that fills the screen loses lines the buffer
     * had room for.
     */
    private static final int MAX_PENDING_LINES = 64;

    /**
     * The screen's storage. Server-side only, like the Machine: the client copy
     * of this block entity holds one nobody ever writes into.
     */
    private final ScreenBuffer screen = new ScreenBuffer(SCREEN_WIDTH, SCREEN_HEIGHT);

    /**
     * What the VM writes into. It stands between the threads that produce
     * output and the buffer, which assumes a single one.
     */
    private final ScreenOutput screenOutput = new ScreenOutput(screen, MAX_PENDING_LINES);

    /**
     * <b>Only the server's copy of this block entity ever uses it.</b> A block
     * entity exists on both sides, and a Machine running on the client would be
     * a second computer nobody can see, with its own thread and its own queue.
     * Every caller below is guarded by level.isClientSide.
     */
    private final Machine machine = new Machine(
            MAX_TASKS_PER_TICK,
            access -> new LuaJVm(screenOutput, INSTRUCTION_BUDGET, access, BoundaryLimits.defaults()),
            BOOT_SCRIPT,
            "boot.lua",
            SIGNAL_QUEUE_CAPACITY);

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(MComputerBlockEntities.COMPUTER, pos, state);
        // Built into the block, as the keyboard is: the inventory that would
        // make it an item is milestone 6. Installed once here and not per boot,
        // because hardware belongs to the machine and survives a reboot. The
        // address is opaque, so stabilising it later breaks no script that did
        // not hardcode one.
        machine.components().add(new Component(UUID.randomUUID(), Gpu.api(screen)));
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
        // Unconditional, and an isRunning() guard would be wrong: at the moment
        // tick() notices the Lua thread is dead and stops the machine, the
        // script's last prints and its failure line are still in the queue.
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

        // Applied on both sides: on the client because that is what the sync is
        // for, and on the server where the key is simply absent. The divergence
        // is carried by the Optional, not by a branch of ours.
        input.read("screen", Codec.BYTE_BUFFER).ifPresent(incoming -> {
            byte[] cells = new byte[incoming.remaining()];
            incoming.get(cells);
            screen.restore(cells, input.getIntOr("screen_row", 0));
        });

        // The sync packet enters through this method, so it runs on the client
        // too. The guard sits between the screen half and the machine half: it
        // guards the Machine, not the method. getLevel() is null here only on
        // the server, where a block entity is deserialised before being
        // attached.
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
        // On the CompoundTag rather than through saveAdditional, which is the
        // whole trick: the disk path never sees these two keys, so "the buffer
        // does not survive a world reload" costs no code.
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
     * machine can turn itself off when a script ends or fails, so toggle() is
     * no longer the only moment the state changes.
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
     * <p>No setChanged() here, unlike syncState(): the screen is synchronised
     * and not saved, so it wants the network half and not the disk half.
     *
     * <p>The if at the call site is not an optimisation either: without it this
     * runs twenty times a second for every computer in the world, over a grid
     * nobody touched.
     */
    private void sendScreen() {
        Level level = getLevel();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(),
                    Block.UPDATE_ALL);
        }
    }

    /**
     * A boot starts on a clean screen; an extinction does not clear it, so the
     * error that killed a script stays readable until the next boot.
     *
     * <p>The only write to the buffer outside the drain, and it is legal
     * because this runs on the server thread, from toggle() and from
     * loadAdditional(). Anything reaching the buffer from another thread goes
     * through screenOutput.
     *
     * <p>A boot script that does not compile leaves the computer off. The
     * failure has already gone out on the machine's output channel, which is
     * the screen, and letting it escape would fail a world load.
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
