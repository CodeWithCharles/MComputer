package io.github.codewithcharles.mcomputer.minecraft.block;

import com.mojang.serialization.MapCodec;
import io.github.codewithcharles.mcomputer.minecraft.block.entity.ComputerBlockEntity;
import io.github.codewithcharles.mcomputer.minecraft.block.entity.MComputerBlockEntities;
import io.github.codewithcharles.mcomputer.minecraft.client.ComputerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

public final class ComputerBlock extends BaseEntityBlock {

    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<ComputerBlock> CODEC = simpleCodec(ComputerBlock::new);

    public ComputerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(LIT, Boolean.FALSE)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<ComputerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT, FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ComputerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit)
    {
        // SUCCESS and not SUCCESS_SERVER, and it is decided rather than
        // inherited. The two differ only in who triggers the arm swing - the
        // client by prediction, or the server on order. Vanilla picks
        // SUCCESS_SERVER where success depends on server state the client
        // cannot see: an occupied bed, a vault on cooldown. Ours always
        // succeeds, so there is nothing to mispredict and the swing is honest.

        // Shift + right-click opens the terminal, plain right-click keeps the
        // toggle. Both assignments are DELIBERATELY PROVISIONAL - decisions.md
        // promises the plain right-click to the components GUI at milestone 4.
        if (player.isSecondaryUseActive()) {
            if (level.isClientSide()
                && level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
                // The only reference in this project from common code to a
                // client-only class. It holds because the JVM resolves a call
                // site on first execution and this branch never runs on a
                // dedicated server - a guard on CLASS LOADING rather than on
                // behaviour, which is subtler than every other guard here and
                // is only truly provable by running a dedicated server.
                ComputerScreen.open(computer);
            }
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            computer.toggle();
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity>BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type)
    {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, MComputerBlockEntities.COMPUTER,
                (tickLevel, pos, tickState, computer) -> computer.serverTick());
    }
}