package io.github.codewithcharles.mcomputer.minecraft.block.entity;

import com.mojang.serialization.Codec;
import io.github.codewithcharles.mcomputer.MComputer;
import io.github.codewithcharles.mcomputer.core.component.BoundaryLimits;
import io.github.codewithcharles.mcomputer.core.component.Component;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import io.github.codewithcharles.mcomputer.core.fs.DiskImage;
import io.github.codewithcharles.mcomputer.core.fs.Filesystem;
import io.github.codewithcharles.mcomputer.core.machine.InstructionBudget;
import io.github.codewithcharles.mcomputer.core.machine.Machine;
import io.github.codewithcharles.mcomputer.core.machine.Signal;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ComputerBlockEntity extends BlockEntity {

    /** TODO: a guess. tickRunsAtMostMaxTasksPerTick proves it reaches drain(). */
    private static final int MAX_TASKS_PER_TICK = 16;

    private static final int SIGNAL_QUEUE_CAPACITY = 256;

    /** TODO: a guess. Per CPU tier at milestone 6. */
    private static final int INSTRUCTIONS_PER_TICK = 20_000;

    /** Tier one, in bytes. */
    private static final long DISK_CAPACITY = 1024 * 1024;

    private static final long DISK_ENTRY_COST = 512;

    private static final int MAX_OPEN_FILES = 16;

    private static final String RESOURCES = "/assets/mcomputer/lua/";

    /**
     * What a fresh disk is given. It stands in for a floppy: at milestone 6 the
     * same tree arrives on an item and this list becomes its contents, so it
     * names disk paths and the resource path is derived from them.
     *
     * <p>The store creates one level at a time, so the directories go first.
     */
    private static final String[] SYSTEM_DIRECTORIES = { "/bin" };

    private static final String[] SYSTEM_FILES = {
        "/boot.lua",
        "/bin/lua.lua",
        "/bin/ls.lua",
        "/bin/cd.lua",
        "/bin/cat.lua",
        "/bin/mkdir.lua",
        "/bin/rm.lua",
        "/bin/mv.lua",
    };

    private static final byte[] BOOT_SCRIPT = """
            local fs
            for address, kind in pairs(component.list()) do
                if kind == 'filesystem' then fs = address end
            end
            local handle = component.invoke(fs, 'open', '/boot.lua', 'r')
            local source = ''
            while true do
                local piece = component.invoke(fs, 'read', handle, 2048)
                if piece == nil then break end
                source = source .. piece
            end
            component.invoke(fs, 'close', handle)
            local chunk, why = load(source, 'boot.lua')
            if not chunk then
                -- print and not gpu.write: a failure's audience is the player,
                -- which is the channel this one was built for.
                print(why)
                return
            end
            chunk()
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
     * Built into the block until an item can hold one. It carries no UUID: a
     * UUID gives an identity to a disk that moves, and nothing moves yet.
     */
    private final DiskImage disk = new DiskImage(DISK_CAPACITY, DISK_ENTRY_COST);

    /**
     * True while {@link #getUpdateTag} builds a packet.
     *
     * <p>saveAdditional has two callers behind one signature, the disk and the
     * network, and the disk must not cross the wire. Removing the key from the
     * finished tag was not enough: saveCustomOnly runs saveAdditional, so the
     * whole disk was snapshotted on every screen change - with a shell, on every
     * keystroke - and then dropped.
     *
     * <p>Server thread only, like everything else here.
     */
    private boolean packingForNetwork;

    /**
     * What the chunk was last told about. setChanged() marks the chunk dirty,
     * so calling it every tick would pay for a save that has nothing to save.
     */
    private long savedRevision;
    private long sentRevision;

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
            (access, budget) -> new LuaJVm(screenOutput, budget, access, BoundaryLimits.defaults()),
            BOOT_SCRIPT,
            "loader",
            SIGNAL_QUEUE_CAPACITY,
            new InstructionBudget(INSTRUCTIONS_PER_TICK));

    /**
     * The keyboard is built into the block. It has an address and no methods -
     * a component nothing can call but a script can find in component.list(),
     * which is the shape ComponentApi was decided to allow before anything
     * needed it.
     */
    private final UUID keyboard = UUID.randomUUID();

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(MComputerBlockEntities.COMPUTER, pos, state);
        // Built into the block, as the keyboard is: the inventory that would
        // make it an item is milestone 6. Installed once here and not per boot,
        // because hardware belongs to the machine and survives a reboot. The
        // address is opaque, so stabilising it later breaks no script that did
        // not hardcode one.
        machine.components().add(new Component(UUID.randomUUID(), Gpu.api(screen)));
        machine.components().add(new Component(keyboard, ComponentApi.builder("keyboard").build()));
        machine.components().add(new Component(UUID.randomUUID(), Filesystem.api(disk, MAX_OPEN_FILES)));
    }

    /**
     * The system is files the player owns. Each is written only when the disk
     * has none, so an edited one is never overwritten and a deleted one comes
     * back at the next boot.
     *
     * <p>A world must load: a disk with no room left costs the player a shell
     * and a line in the log rather than a crash. Same shape as a snapshot that
     * will not read.
     */
    private void installSystemIfAbsent() {
        try {
            for (String directory : SYSTEM_DIRECTORIES) {
                disk.makeDirectory(directory);
            }
            for (String path : SYSTEM_FILES) {
                if (disk.exists(path)) {
                    continue;
                }
                disk.createFile(path);
                disk.write(path, 0, resource(RESOURCES + path.substring(1)));
            }
        } catch (ComponentException noRoom) {
            MComputer.LOGGER.error("computer at {} could not install its system",
                    getBlockPos(), noRoom);
        }
    }

    private static byte[] resource(String path) {
        InputStream source = ComputerBlockEntity.class.getResourceAsStream(path);
        if (source == null) {
            throw new IllegalStateException("missing from the mod jar: " + path);
        }
        try (source) {
            return source.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException("could not be read: " + path, unreadable);
        }
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
        // The drain still has no guard, for the reason it never had one: a
        // dying script's last lines are still queued. But what decides to send
        // is the buffer, not the queue - the graphics card writes through the
        // CallQueue and never touches ScreenOutput, so watching the queue misses
        // every write a shell makes.
        screenOutput.drain();
        if (screen.revision() != sentRevision) {
            sentRevision = screen.revision();
            sendScreen();
        }
        // sendScreen() deliberately does not call setChanged(), so a script
        // that writes a file without printing would never make the chunk dirty
        // and its work would never reach the disk.
        if (disk.revision() != savedRevision) {
            savedRevision = disk.revision();
            setChanged();
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
        if (!packingForNetwork) {
            output.store("disk", Codec.BYTE_BUFFER, ByteBuffer.wrap(disk.snapshot()));
        }
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

        input.read("disk", Codec.BYTE_BUFFER).ifPresent(saved -> {
            byte[] snapshot = new byte[saved.remaining()];
            saved.get(snapshot);
            try {
                disk.restore(snapshot);
            } catch (ComponentException unreadable) {
                // A world must load. The player gets an empty disk and a line
                // in the log rather than a crash he cannot act on.
                MComputer.LOGGER.error("computer at {} could not read its disk",
                        getBlockPos(), unreadable);
            }
        });
        savedRevision = disk.revision();

        if (input.getBooleanOr("running", false)) {
            startQuietly();
        } else {
            machine.stop();
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        packingForNetwork = true;
        CompoundTag tag;
        try {
            tag = saveCustomOnly(registries);
        } finally {
            packingForNetwork = false;
        }
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
        installSystemIfAbsent();
        screen.clear();
        try {
            machine.start();
        } catch (VmException e) {
            // already reported on the output channel; the computer stays off
        }
    }

    /**
     * A key pressed at this computer by a player the network layer has already
     * found in range. The keyboard's address comes first, and pushSignal
     * answering false for a stopped machine is the designed answer rather than
     * a case to branch on.
     */
    public void keyDown(ServerPlayer player, int character, int code) {
        machine.pushSignal(new Signal("key_down", new Object[] {
                keyboard.toString().getBytes(StandardCharsets.UTF_8),
                (double) character,
                (double) code,
                player.getName().getString().getBytes(StandardCharsets.UTF_8) }));
    }
}
