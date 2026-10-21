package com.idm.test.runner.ui;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The folder picker behind <b>Browse…</b>: the sub-folders of one folder on this computer. Only
 * folders, no files; names starting with {@code .} are left out. What cannot be read is passed
 * over rather than failing the listing: an unreadable sub-folder is not listed, an unreadable
 * folder lists nothing, and a symbolic link that loops (or leads back to the folder or one of its
 * parents) is not listed.
 */
public final class FolderBrowser {

    private FolderBrowser() {
    }

    /** One folder's sub-folders, sorted by name; {@code parent} is null at a root. */
    public record Listing(String path, String parent, List<Entry> entries) {
    }

    /** A sub-folder; {@code isWorkspace} when it has a {@code scenarios/} folder. */
    public record Entry(String name, String path, @JsonProperty("isWorkspace") boolean isWorkspace) {
    }

    /** {@code folder} is not an absolute path to an existing folder. */
    public static final class NotAFolderException extends RuntimeException {
        NotAFolderException(String path) {
            super(path);
        }
    }

    /** The sub-folders of {@code folder}, which must be an absolute path to a folder. */
    public static Listing list(Path folder) {
        if (!folder.isAbsolute() || !Files.isDirectory(folder)) {
            throw new NotAFolderException(folder.toString());
        }
        Path dir = folder.normalize();
        Path real = realPath(dir);
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                Entry entry = entry(child, real);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException e) {
            // unreadable, or became so while listing: what was read so far
        }
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.SECONDARY);
        entries.sort(Comparator.comparing(Entry::name, collator));
        Path parent = dir.getParent();
        return new Listing(dir.toString(), parent == null ? null : parent.toString(), List.copyOf(entries));
    }

    /** The entry for {@code child}, or null when it is not to be listed. */
    private static Entry entry(Path child, Path listedReal) {
        try {
            Path name = child.getFileName();
            if (name == null || name.toString().startsWith(".") || !Files.isDirectory(child)
                    || !Files.isReadable(child)) {
                return null;
            }
            if (Files.isSymbolicLink(child)) {
                Path target = child.toRealPath();
                if (listedReal != null && listedReal.startsWith(target)) {
                    return null;
                }
            }
            return new Entry(name.toString(), child.toString(), Files.isDirectory(child.resolve("scenarios")));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static Path realPath(Path dir) {
        try {
            return dir.toRealPath();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
