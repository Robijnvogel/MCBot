package com.blamejared.mcbot.commands;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.commons.lang3.ArrayUtils;

import com.blamejared.mcbot.MCBot;
import com.blamejared.mcbot.commands.CommandQuote.Quote;
import com.blamejared.mcbot.commands.api.Argument;
import com.blamejared.mcbot.commands.api.Command;
import com.blamejared.mcbot.commands.api.CommandContext;
import com.blamejared.mcbot.commands.api.CommandException;
import com.blamejared.mcbot.commands.api.CommandPersisted;
import com.blamejared.mcbot.commands.api.Flag;
import com.blamejared.mcbot.util.BakedMessage;
import com.blamejared.mcbot.util.PaginatedMessageFactory;
import com.blamejared.mcbot.util.PaginatedMessageFactory.PaginatedMessage;
import com.blamejared.mcbot.util.RequestHelper;
import com.blamejared.mcbot.util.Requirements;
import com.blamejared.mcbot.util.Requirements.RequiredType;
import com.blamejared.mcbot.util.Threads;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.vdurmont.emoji.Emoji;
import com.vdurmont.emoji.EmojiManager;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.val;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import sx.blah.discord.api.events.EventSubscriber;
import sx.blah.discord.api.internal.json.objects.EmbedObject;
import sx.blah.discord.handle.impl.events.guild.channel.message.reaction.ReactionAddEvent;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.handle.obj.Permissions;
import sx.blah.discord.util.EmbedBuilder;
import sx.blah.discord.util.RequestBuffer;

@Command
public class CommandQuote extends CommandPersisted<Map<Integer, Quote>> {
    
    private class BattleManager {
        
        private final Map<IChannel, IMessage> battles = new HashMap<>();
        private final Set<IMessage> allBattles = new HashSet<>();
        
        private final long voteTime = TimeUnit.MINUTES.toMillis(1);

        private final Emoji ONE = EmojiManager.getForAlias("one");
        private final Emoji TWO = EmojiManager.getForAlias("two");
        private final Emoji CANCEL = EmojiManager.getForAlias("x");
        private final Emoji SKULL = EmojiManager.getForAlias("skull");
        
        @EventSubscriber
        public void onReactAdd(ReactionAddEvent event) {
            Emoji emoji = event.getReaction().getUnicodeEmoji();
            if (allBattles.contains(event.getMessage())) {
                if (emoji != ONE && emoji != TWO && emoji != CANCEL) {
                    RequestBuffer.request(() -> event.getMessage().removeReaction(event.getReaction()));
                } else if (!event.getUser().equals(MCBot.instance.getOurUser())) {
                    event.getMessage().getReactions().stream().filter(r -> !r.equals(event.getReaction()) && r.getUsers().contains(event.getUser())).forEach(r -> 
                        RequestBuffer.request(() -> event.getMessage().removeReaction(event.getUser(), r))
                    );
                }
            }
        }
        
        public boolean canStart(CommandContext ctx) {
            return !battles.containsKey(ctx.getChannel());
        }
        
        private int randomQuote(Map<Integer, Quote> map) throws CommandException {
            int totalWeight = map.values().stream().mapToInt(Quote::getWeight).sum();
            int choice = rand.nextInt(totalWeight);
            for (val e : map.entrySet()) {
                if (choice < e.getValue().getWeight()) {
                    return e.getKey();
                }
                choice -= e.getValue().getWeight();
            }
            throw new CommandException("Ran out of quotes? This should not happen!");
        }
        
        public void battle(CommandContext ctx) throws CommandException {
            if (storage.get(ctx).size() < 2) {
                throw new CommandException("There must be at least two quotes to battle!");
            }
            // Copy storage map so as to not alter it
            Map<Integer, Quote> tempMap = Maps.newHashMap(storage.get(ctx));
            int q1 = randomQuote(tempMap);
            // Make sure the same quote isn't picked twice
            tempMap.remove(q1);
            int q2 = randomQuote(tempMap);

            Quote quote1 = storage.get(ctx).get(q1);
            Quote quote2 = storage.get(ctx).get(q2);
            
            EmbedObject embed = new EmbedBuilder()
                    .withTitle("QUOTE BATTLE")
                    .withDesc("Vote for the quote you want to win! Vote " + CANCEL.getUnicode() + " to call off the battle!")
                    .appendField("Quote 1", "#" + q1 + ": " + quote1, false)
                    .appendField("Quote 2", "#" + q2 + ": " + quote2, false)
                    .build();
            
            IMessage msg;
            battles.put(ctx.getChannel(), msg = ctx.reply(embed));
            allBattles.add(msg);
            RequestHelper.requestOrdered(
                    () -> msg.addReaction(ONE),
                    () -> msg.addReaction(TWO),
                    () -> msg.addReaction(CANCEL)
            );
            
            Threads.sleep(voteTime);
            
            IMessage result = ctx.getChannel().getMessageByID(msg.getLongID());
            
            int votes1 = result.getReactionByUnicode(ONE).getCount();
            int votes2 = result.getReactionByUnicode(TWO).getCount();
            int cancelVotes = result.getReactionByUnicode(CANCEL).getCount();
            
            // If there are less than three votes, call it off
            if (votes1 + votes2 + cancelVotes - 3 < 3) {
                ctx.replyBuffered("That's not enough votes for me to commit murder, sorry.");
            } else if (cancelVotes >= Math.max(votes1, votes2)) {
                ctx.replyBuffered("Battle canceled, boooooo!");
            } else if (votes1 == votes2) {
                ctx.replyBuffered("It's a tie, we're all losers today.");
            } else {
                int winner = votes1 > votes2 ? q1 : q2;
                int loser = winner == q1 ? q2 : q1;
                Quote winnerQuote = winner == q1 ? quote1 : quote1;
                winnerQuote.onWinBattle();
                ctx.replyBuffered("Quote #" + winner + " is the winner! Quote #" + loser + " is eliminated!");
                EmbedObject tombstone = new EmbedBuilder()
                        .withTitle(SKULL.getUnicode() + " Here lies quote #" + loser + ". May it rest in peace. " + SKULL.getUnicode())
                        .withDescription(loser == q1 ? quote1.toString() : quote2.toString())
                        .build();
                ctx.replyBuffered(tombstone);
                storage.get(ctx).remove(loser);
            }
            
            battles.remove(ctx.getChannel());
            allBattles.remove(msg);
            
            msg.delete();
        }
    }
    
    @RequiredArgsConstructor
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode
    @Getter
    static class Quote {
        
        private static final String QUOTE_FORMAT = "\"%s\" - %s";
        
        String quote, quotee;
        long owner;
        @NonFinal
        int weight = 1024;
        
        public void onWinBattle() {
            weight /= 2;
        }
        
        @Override
        public String toString() {
            return String.format(QUOTE_FORMAT, getQuote(), getQuotee());
        }
    }
    
    private static final Flag FLAG_LS = new SimpleFlag("ls", "Lists all current quotes.", true, "0");
    private static final Flag FLAG_ADD = new SimpleFlag("add", "Adds a new quote.", true);
    private static final Flag FLAG_REMOVE = new SimpleFlag("remove", "Removes a quote by its ID.", true);
    private static final Flag FLAG_BATTLE = new SimpleFlag("battle", "Get ready to rrruuummmbbbllleee!", false);
    
    private static final Argument<Integer> ARG_ID = new IntegerArgument("quote", "The id of the quote to display.", false);
    
    private static final int PER_PAGE = 10;
    
    private static final Requirements REMOVE_PERMS = Requirements.builder().with(Permissions.MANAGE_MESSAGES, RequiredType.ALL_OF).build();
    
    private final BattleManager battleManager = new BattleManager();
    
    public CommandQuote() {
        super("quote", false, Lists.newArrayList(FLAG_LS, FLAG_ADD, FLAG_REMOVE, FLAG_BATTLE), Lists.newArrayList(ARG_ID), HashMap::new);
//        quotes.put(id++, "But noone cares - HellFirePVP");
//        quotes.put(id++, "CRAFTTWEAKER I MEANT CRAFTTWEAKER - Drullkus");
//        quotes.put(id++, "oh yeah im dumb - Kit");
//        quotes.put(id++, "i call zenscripts \"mt scripts\" - Kit");
//        quotes.put(id++, "yes - Shadows");
    }
    
    @Override
    protected TypeToken<Map<Integer, Quote>> getDataType() {
        return new TypeToken<Map<Integer, Quote>>(){};
    }
    
    @Override
    public void onRegister() {
        super.onRegister();
        MCBot.instance.getDispatcher().registerListener(battleManager);
    }
    
    private final Pattern IN_QUOTES_PATTERN = Pattern.compile("\".*\"");

    @Override
    public void gatherParsers(GsonBuilder builder) {
        super.gatherParsers(builder);
        builder.registerTypeAdapter(Quote.class, new JsonDeserializer<Quote>() {
            @Override
            public Quote deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                    String quote = json.getAsString();
                    if (IN_QUOTES_PATTERN.matcher(quote.trim()).matches()) {
                        quote = quote.trim().replace("\"", "");
                    }
                    int lastDash = quote.lastIndexOf('-');
                    String author = lastDash < 0 ? "Anonymous" : quote.substring(lastDash + 1);
                    quote = lastDash < 0 ? quote : quote.substring(0, lastDash);
                    // run this twice in case the quotes were only around the "quote" part
                    if (IN_QUOTES_PATTERN.matcher(quote.trim()).matches()) {
                        quote = quote.trim().replace("\"", "");
                    }
                    return new Quote(quote.trim(), author.trim(), MCBot.instance.getOurUser().getLongID());
                }
                return new Gson().fromJson(json, Quote.class);
            }
        });
    }
    
    Random rand = new Random();

    @Override
    public void process(CommandContext ctx) throws CommandException {
        if (ctx.hasFlag(FLAG_LS)) {
            Map<Integer, Quote> quotes = storage.get(ctx.getMessage());
            
            int pageTarget = 0;
            int maxPages = ((quotes.size() - 1) / PER_PAGE) + 1;
            try {
                String pageStr = ctx.getFlag(FLAG_LS);
                if (pageStr != null) {
                    pageTarget = Integer.parseInt(ctx.getFlag(FLAG_LS)) - 1;
                    if (pageTarget < 0 || pageTarget >= maxPages) {
                        throw new CommandException("Page argument out of range!");
                    }
                }
            } catch (NumberFormatException e) {
                throw new CommandException(ctx.getFlag(FLAG_LS) + " is not a valid number!");
            }

            int count = 0;
            StringBuilder builder = null;
            PaginatedMessageFactory.Builder messagebuilder = PaginatedMessageFactory.INSTANCE.builder(ctx.getChannel());
            for (val e : quotes.entrySet()) {
            	int page = (count / PER_PAGE) + 1;
            	if (count % PER_PAGE == 0) {
            		if (builder != null) {
            			messagebuilder.addPage(new BakedMessage().withContent(builder.toString()));
            		}
            		builder = new StringBuilder();
            		builder.append("List of quotes (Page " + page + "/" + maxPages + "):\n");
            	}
                builder.append(e.getKey()).append(") ").append(e.getValue()).append("\n");
                count++;
            }
            messagebuilder.addPage(new BakedMessage().withContent(builder.toString()));
            PaginatedMessage msg = messagebuilder.setParent(ctx.getMessage()).build();
            msg.setPage(pageTarget);
            msg.send();
            return;
        } else if (ctx.hasFlag(FLAG_ADD)) {
            String quote = ctx.getFlag(FLAG_ADD);
            String author;
            int idx = quote.lastIndexOf('-');
            if (idx > 0) {
                author = quote.substring(idx + 1).trim();
                quote = quote.substring(0, idx).trim();
            } else {
                author = "Anonymous";
            }

            Map<Integer, Quote> quotes = storage.get(ctx.getMessage());
            int id = quotes.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            quotes.put(id, new Quote(ctx.sanitize(quote), ctx.sanitize(author), ctx.getAuthor().getLongID()));
            ctx.reply("Added quote #" + id + "!");
            return;
        } else if (ctx.hasFlag(FLAG_REMOVE)) {
            if (!REMOVE_PERMS.matches(ctx.getAuthor(), ctx.getGuild())) {
                throw new CommandException("You do not have permission to remove quotes!");
            }
            int index = Integer.parseInt(ctx.getFlag(FLAG_REMOVE));
            Quote removed = storage.get(ctx.getMessage()).remove(index);
            if (removed != null) {
                ctx.reply("Removed quote!");
            } else {
                throw new CommandException("No quote for ID " + index);
            }
            return;
        } else if (ctx.hasFlag(FLAG_BATTLE)) {
            if (!REMOVE_PERMS.matches(ctx.getAuthor(), ctx.getGuild())) {
                throw new CommandException("You do not have permission to start a battle!");
            }
            if (!battleManager.canStart(ctx)) {
                throw new CommandException("Cannot start a battle, one already exists in this channel!");
            }
            battleManager.battle(ctx);
            return;
        }
        
        String quoteFmt = "#%d: %s";
        if(ctx.argCount() == 0) {
            Integer[] keys = storage.get(ctx.getMessage()).keySet().toArray(new Integer[0]);
            if (keys.length == 0) {
                throw new CommandException("There are no quotes!");
            }
            int id = rand.nextInt(keys.length);
            ctx.reply(String.format(quoteFmt, id, storage.get(ctx).get(keys[id])));
        } else {
            int id = ctx.getArg(ARG_ID);
            Quote quote = storage.get(ctx.getMessage()).get(id);
            if (quote != null) {
                ctx.reply(String.format(quoteFmt, id, quote));
            } else {
                throw new CommandException("No quote for ID " + id);
            }
        }
    }
    
    @Override
    public String getDescription() {
        return "A way to store and retrieve quotes.";
    }
}
