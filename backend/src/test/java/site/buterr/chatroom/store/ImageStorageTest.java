package site.buterr.chatroom.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import site.buterr.chatroom.model.ChatImage;

class ImageStorageTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesReadsAndDeletesImage() throws Exception {
        ImageStorage storage = new ImageStorage(temporaryDirectory.resolve("uploads"));

        ChatImage image = storage.store("../pixel.png", PNG);
        assertEquals("pixel.png", image.originalName());
        assertEquals("image/png", image.mediaType());
        assertEquals(PNG.length, image.size());
        assertArrayEquals(PNG, storage.read(image));

        storage.delete(image);
        assertThrows(NoSuchFileException.class, () -> storage.read(image));
    }

    @Test
    void rejectsContentThatIsNotASupportedImage() throws Exception {
        ImageStorage storage = new ImageStorage(temporaryDirectory.resolve("uploads"));

        assertThrows(InvalidImageException.class,
                () -> storage.store("fake.png", "this is not an image".getBytes()));
    }
}
