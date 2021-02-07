/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.commands.subs;

import me.filoghost.fcommons.command.CommandContext;
import me.filoghost.fcommons.command.sub.SubCommandContext;
import me.filoghost.fcommons.command.validation.CommandException;
import me.filoghost.fcommons.command.validation.CommandValidate;
import me.filoghost.holographicdisplays.Colors;
import me.filoghost.holographicdisplays.commands.HologramCommandValidate;
import me.filoghost.holographicdisplays.commands.Messages;
import me.filoghost.holographicdisplays.disk.HologramDatabase;
import me.filoghost.holographicdisplays.event.NamedHologramEditedEvent;
import me.filoghost.holographicdisplays.image.ImageReader;
import me.filoghost.holographicdisplays.image.ImageTooWideException;
import me.filoghost.holographicdisplays.image.ImageReadException;
import me.filoghost.holographicdisplays.image.ImageMessage;
import me.filoghost.holographicdisplays.object.NamedHologram;
import me.filoghost.holographicdisplays.object.line.CraftTextLine;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ReadimageCommand extends LineEditingCommand {
    
    public ReadimageCommand() {
        super("readimage", "image");
        setMinArgs(3);
        setUsageArgs("<hologram> <imageWithExtension> <width>");
    }

    @Override
    public List<String> getDescription(CommandContext context) {
        return Arrays.asList(
                "Reads an image from a file. Tutorial:",
                "1) Move the image in the plugin's folder",
                "2) Do not use spaces in the name",
                "3) Do " + getFullUsageText(context),
                "4) Choose <width> to automatically resize the image",
                "5) (Optional) Use the flag '-a' if you only want to append",
                "   the image to the hologram without clearing the lines",
                "",
                "Example: you have an image named 'logo.png', you want to append",
                "it to the lines of the hologram named 'test', with a width of",
                "50 pixels. In this case you would execute the following command:",
                ChatColor.YELLOW + "/" + context.getRootLabel() + " " + getName() + " test logo.png 50 -a",
                "",
                "The symbols used to create the image are taken from the config.yml.");
    }
    
    @Override
    public void execute(CommandSender sender, String[] args, SubCommandContext context) throws CommandException {
        
        boolean append = false;
        
        List<String> newArgs = new ArrayList<>();

        for (String arg : args) {
            if (arg.equalsIgnoreCase("-a") || arg.equalsIgnoreCase("-append")) {
                append = true;
            } else {
                newArgs.add(arg);
            }
        }
        
        args = newArgs.toArray(new String[0]);
        
        NamedHologram hologram = HologramCommandValidate.getNamedHologram(args[0]);
        
        int width = CommandValidate.parseInteger(args[2]);
        
        CommandValidate.check(width >= 2, "The width of the image must be 2 or greater.");

        boolean isUrl = false;
        
        try {
            String fileName = args[1];
            BufferedImage image;
            
            if (fileName.startsWith("http://") || fileName.startsWith("https://")) {
                isUrl = true;
                image = ImageReader.readImage(new URL(fileName));
            } else {
                
                if (fileName.matches(".*[a-zA-Z0-9\\-]+\\.[a-zA-Z0-9\\-]{1,4}\\/.+")) {
                    Messages.sendWarning(sender, "The image path seems to be an URL. If so, please use http:// or https:// in the path.");
                }

                Path targetImage = HologramCommandValidate.getUserReadableFile(fileName);
                image = ImageReader.readImage(targetImage);
            }
            
            if (!append) {
                hologram.clearLines();
            }
            
            ImageMessage imageMessage = new ImageMessage(image, width);
            String[] newLines = imageMessage.getLines();
            for (String newLine : newLines) {
                CraftTextLine line = new CraftTextLine(hologram, newLine);
                line.setSerializedConfigValue(newLine);
                hologram.getLinesUnsafe().add(line);
            }
            
            hologram.refreshAll();
            
            if (newLines.length < 5) {
                Messages.sendTip(sender, "The image has a very low height. You can increase it by increasing the width, it will scale automatically.");
            }
            
            HologramDatabase.saveHologram(hologram);
            HologramDatabase.trySaveToDisk();
            
            if (append) {
                sender.sendMessage(Colors.PRIMARY + "The image was appended int the end of the hologram!");
            } else {
                sender.sendMessage(Colors.PRIMARY + "The image was drawn in the hologram!");
            }
            Bukkit.getPluginManager().callEvent(new NamedHologramEditedEvent(hologram));
            
        } catch (MalformedURLException e) {
            throw new CommandException("The provided URL was not valid.");
        } catch (ImageTooWideException e) {
            throw new CommandException("The image is too large. Max width allowed is " + ImageMessage.MAX_WIDTH + " pixels.");
        } catch (ImageReadException e) {
            throw new CommandException("The plugin was unable to read the image. Be sure that the format is supported.");
        } catch (IOException e) {
            e.printStackTrace();
            throw new CommandException("I/O exception while reading the image. " + (isUrl ? "Is the URL valid?" : "Is it in use?"));
        }
    }

}
