/*
 * Copyright 2016 John Grosh (jagrosh).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spectra;

import java.io.File;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;
import net.dv8tion.jda.JDA;
import net.dv8tion.jda.entities.Guild;
import net.dv8tion.jda.entities.TextChannel;
import spectra.datasources.Feeds;
import spectra.entities.Tuple;
import spectra.utils.FormatUtil;

/**
 *
 * @author John Grosh (jagrosh)
 */
public class FeedHandler {
    private static final FeedHandler handler = new FeedHandler();
    private final HashMap<String,String> buffers = new HashMap<>();
    
    private FeedHandler(){}
    
    public static FeedHandler getInstance()
    {
        return handler;
    }
    
    public void flush(JDA jda)
    {
        synchronized(buffers){
            buffers.keySet().stream().forEach((id) -> {
                TextChannel tc = jda.getTextChannelById(id);
                if (!(tc==null)) {
                    if (!(buffers.get(id)==null || buffers.get(id).equals(""))) {
                        Sender.sendFeed(buffers.get(id), tc);
                        buffers.put(id, "");
                    }
                }
            });
        }
    }
    
    public void submitText(Feeds.Type type, Guild guild, String text)
    {
        submitText(type,Collections.singletonList(guild),text);
    }
    
    public void submitFile(Feeds.Type type, Guild guild, Supplier<Tuple<String,File>> message, String alternative)
    {
        submitFile(type, Collections.singletonList(guild), message, alternative);
    }
    
    public void submitText(Feeds.Type type, List<Guild> guilds, String text)
    {
        if(type==Feeds.Type.MODLOG || type== Feeds.Type.SERVERLOG || type==Feeds.Type.TAGLOG)
            text = logFormat(text);
        else if (type==Feeds.Type.BOTLOG)
            text = botlogFormat(text);
        synchronized(buffers)
        {
            for(Guild guild : guilds)
            {
                String[] matching = Feeds.getInstance().feedForGuild(guild, type);
                if(matching==null)
                    continue;
                TextChannel target = guild.getJDA().getTextChannelById(matching[Feeds.CHANNELID]);
                if(target==null)
                {
                    if(guild.isAvailable())//channel was deleted
                    {
                        Feeds.getInstance().removeFeed(matching);
                        buffers.remove(matching[Feeds.CHANNELID]);
                    }
                    continue;
                }

                String currentBuffer;
                currentBuffer = buffers.get(matching[Feeds.CHANNELID]);

                if(currentBuffer==null)
                    currentBuffer = "";

                boolean safe = true;

                if(currentBuffer.length()+text.length()+1 > 2000)//flush current buffer
                {
                    safe = Sender.sendFeed(currentBuffer, target);
                    currentBuffer = "";
                    if(!safe)
                    {
                        Feeds.getInstance().removeFeed(matching);
                        Sender.sendPrivate(SpConst.WARNING+"Feed `"+matching[Feeds.FEEDTYPE]+"` has been removed from <#"+target.getId()+"> because I cannot send messages there.", guild.getOwner().getPrivateChannel());
                    }
                }

                if(safe)
                {
                    currentBuffer += "\n"+text;
                    if(currentBuffer.length() > 1800)
                    {
                        safe = Sender.sendFeed(currentBuffer, target);
                        currentBuffer = "";
                        if(!safe)
                        {
                            Feeds.getInstance().removeFeed(matching);
                            Sender.sendPrivate(SpConst.WARNING+"Feed `"+matching[Feeds.FEEDTYPE]+"` has been removed from <#"+target.getId()+"> because I cannot send messages there.", guild.getOwner().getPrivateChannel());
                        }
                    }
                }
                buffers.put(matching[Feeds.CHANNELID], currentBuffer);
            }
        }
    }
    
    public void submitFile(Feeds.Type type, List<Guild> guilds, Supplier<Tuple<String,File>> message, String alternative)
    {
        if(type==Feeds.Type.MODLOG || type== Feeds.Type.SERVERLOG || type==Feeds.Type.TAGLOG)
            alternative = logFormat(alternative);
        else if (type==Feeds.Type.BOTLOG)
            alternative = botlogFormat(alternative);
        File file = null;
        String normal = null;
        synchronized(buffers)
        {
            for(Guild guild : guilds)
            {
                String[] matching = Feeds.getInstance().feedForGuild(guild, type);
                if(matching==null)
                    continue;
                TextChannel target = guild.getJDA().getTextChannelById(matching[Feeds.CHANNELID]);
                if(target==null)
                {
                    if(guild.isAvailable())//channel was deleted
                    {
                        Feeds.getInstance().removeFeed(matching);
                        buffers.remove(matching[Feeds.CHANNELID]);
                    }
                    continue;
                }

                String currentBuffer;

                currentBuffer = buffers.get(matching[Feeds.CHANNELID]);

                if(currentBuffer==null)
                    currentBuffer = "";

                if(file==null)
                {
                    Tuple<String,File> item = message.get();
                    file = item.getSecond();
                    normal = item.getFirst();
                    if(type==Feeds.Type.MODLOG || type== Feeds.Type.SERVERLOG || type==Feeds.Type.TAGLOG)
                        normal = logFormat(normal);
                    else if (type==Feeds.Type.BOTLOG)
                        normal = botlogFormat(normal);
                }

                boolean safe =true;
                if(!currentBuffer.equals(""))
                {
                    safe = Sender.sendFeed(currentBuffer, target);
                    currentBuffer = "";
                    if(!safe)
                    {
                        Feeds.getInstance().removeFeed(matching);
                        Sender.sendPrivate(SpConst.WARNING+"Feed `"+matching[Feeds.FEEDTYPE]+"` has been removed from <#"+target.getId()+"> because I cannot send messages there.", guild.getOwner().getPrivateChannel());
                    }
                }

                if(safe)
                {
                    safe = Sender.sendFeedFile(normal, file, alternative, target);
                    if(!safe)
                    {
                        Feeds.getInstance().removeFeed(matching);
                        Sender.sendPrivate(SpConst.WARNING+"Feed `"+matching[Feeds.FEEDTYPE]+"` has been removed from <#"+target.getId()+"> because I cannot send messages there.", guild.getOwner().getPrivateChannel());
                    }
                }

                buffers.put(matching[Feeds.CHANNELID], currentBuffer);
            }
        }
    }
    
    private static String logFormat(String text)
    {
        return "`["+OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME).substring(0,8)+"]` "+FormatUtil.demention(text);
    }
    
    private static String botlogFormat(String text)
    {
        return "`["+OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME).substring(0,8)+"]` "+text;
    }
    
    /*
        a ChannelFeed object 
    */

}
