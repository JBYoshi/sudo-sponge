/*
 * MIT License
 *
 * Copyright (c) 2018 Jonathan Browne
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package jbyoshi.sponge.sudo;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandCallable;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandMapping;
import org.spongepowered.api.command.CommandNotFoundException;
import org.spongepowered.api.command.CommandPermissionException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.source.ProxySource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.service.permission.Subject;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.world.Locatable;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

@Plugin(id = "sudo")
public final class Sudo implements CommandCallable {

    // Can't use Player as keys because the object is deleted at respawn
    // Can't use weakKeys() because the keys are UUIDs
    // Can't use weakValues() because other places may only store them in weak values
    // Caches are invalidated in onLogOut()
    private final Cache<UUID, CommandSource> sudoers = CacheBuilder.newBuilder().build();

    @Listener
    public void onInit(GameInitializationEvent e) {
        Sponge.getCommandManager().register(this, this, "sudo");
    }

    @Listener
    public void onLogOut(ClientConnectionEvent.Disconnect e) {
        sudoers.invalidate(e.getTargetEntity().getUniqueId());
    }

    @Override
    public CommandResult process(CommandSource source, String arguments) throws CommandException {
        if (!testPermission(source)) {
            throw new CommandPermissionException();
        }
        String[] commandLine = arguments.split(" ", 2);
        if (arguments.trim().isEmpty()) {
            throw new CommandException(Text.of("Please specify a command"));
        }
        Optional<? extends CommandMapping> command = Sponge.getCommandManager().get(commandLine[0]);
        if (!command.isPresent()) {
            throw new CommandNotFoundException(commandLine[0]);
        }
        return command.get().getCallable().process(wrap((Player) source), commandLine.length == 1 ? "" : commandLine[1]);
    }

    @Override
    public List<String> getSuggestions(CommandSource source, String arguments, @Nullable Location<World> targetPosition) throws CommandException {
        if (!testPermission(source)) {
            return Collections.emptyList();
        }
        Player player = (Player) source;
        String[] commandLine = arguments.split(" ", 2);
        if (commandLine.length <= 1) {
            String prefix = commandLine.length == 0 ? "" : commandLine[0];
            return Sponge.getCommandManager().getAliases().stream().filter(x -> x.startsWith(prefix)).collect(Collectors.toList());
        }
        Optional<? extends CommandMapping> command = Sponge.getCommandManager().get(commandLine[0]);
        if (!command.isPresent()) {
            return ImmutableList.of();
        }
        return command.get().getCallable().getSuggestions(wrap(player), commandLine[1], targetPosition);
    }

    private CommandSource wrap(Player player) {
        try {
            return sudoers.get(player.getUniqueId(),
                    () -> new BindingProxy<>(Sudo.class.getClassLoader(), ProxySource.class, Locatable.class) // TODO maybe add ConsoleSource to this?
                    .bind("getOriginalSource").to((p, a) -> player)
                    .bind("getCommandSource").to((p, a) -> p)
                    .bindAll(Subject.class, Sponge.getServer().getConsole())
                    .bindAll(Player.class, player)
                    .build());
        } catch (ExecutionException e) {
            Throwables.throwIfUnchecked(e.getCause());
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean testPermission(CommandSource source) {
        return source instanceof Player && ((Player) source).getConnection().getAddress().getPort() == 0;
    }

    @Override
    public Optional<Text> getShortDescription(CommandSource source) {
        return Optional.of(Text.of("Runs a command with full permissions."));
    }

    @Override
    public Optional<Text> getHelp(CommandSource source) {
        return Optional.of(Text.of("Runs a command with full permissions. This command is only available in a world hosted on the Minecraft client,"
                + " and can only be executed by the owner."));
    }

    @Override
    public Text getUsage(CommandSource source) {
        return Text.of("<command> [command arguments...]");
    }
}
