package edu.illinois.library.cantaloupe.resource;

import edu.illinois.library.cantaloupe.cache.Cache;
import edu.illinois.library.cantaloupe.cache.CacheFactory;
import edu.illinois.library.cantaloupe.image.OperationList;
import edu.illinois.library.cantaloupe.image.SourceFormat;
import edu.illinois.library.cantaloupe.processor.ChannelProcessor;
import edu.illinois.library.cantaloupe.processor.FileProcessor;
import edu.illinois.library.cantaloupe.processor.Processor;
import edu.illinois.library.cantaloupe.processor.ProcessorFactory;
import edu.illinois.library.cantaloupe.util.IOUtils;
import edu.illinois.library.cantaloupe.util.TeeWritableByteChannel;
import org.restlet.data.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Restlet representation for images.
 */
public class ImageRepresentation extends AbstractImageRepresentation {

    private static Logger logger = LoggerFactory.
            getLogger(ImageRepresentation.class);

    public static final String CONTENT_DISPOSITION_CONFIG_KEY =
            "http.content_disposition";
    public static final String FILENAME_CHARACTERS = "[^A-Za-z0-9._-]";

    private File file;
    private Dimension fullSize;
    private ReadableByteChannel readableChannel;
    private OperationList ops;
    private SourceFormat sourceFormat;

    /**
     * Constructor for images from InputStreams.
     *
     * @param mediaType
     * @param sourceFormat
     * @param fullSize
     * @param ops
     * @param readableChannel
     */
    public ImageRepresentation(final MediaType mediaType,
                               final SourceFormat sourceFormat,
                               final Dimension fullSize,
                               final OperationList ops,
                               final ReadableByteChannel readableChannel) {
        super(mediaType, ops.getIdentifier(), ops.getOutputFormat());
        this.readableChannel = readableChannel;
        this.ops = ops;
        this.sourceFormat = sourceFormat;
        this.fullSize = fullSize;
    }

    /**
     * Constructor for images from Files.
     *
     * @param mediaType
     * @param sourceFormat
     * @param fullSize
     * @param ops
     * @param file
     */
    public ImageRepresentation(MediaType mediaType,
                               SourceFormat sourceFormat,
                               Dimension fullSize,
                               OperationList ops,
                               File file) {
        super(mediaType, ops.getIdentifier(), ops.getOutputFormat());
        this.file = file;
        this.fullSize = fullSize;
        this.ops = ops;
        this.sourceFormat = sourceFormat;
    }

    /**
     * Writes the source image to the given output stream.
     *
     * @param writableChannel Response body channel supplied by Restlet
     * @throws IOException
     */
    @Override
    public void write(WritableByteChannel writableChannel) throws IOException {
        Cache cache = CacheFactory.getInstance();
        try {
            if (cache != null) {
                WritableByteChannel cacheWritableChannel = null;
                try (ReadableByteChannel cacheReadableChannel =
                             cache.getImageReadableChannel(this.ops)) {
                    if (cacheReadableChannel != null) {
                        // a cached image is available; write it to the
                        // response output stream.
                        IOUtils.copy(cacheReadableChannel, writableChannel);
                    } else {
                        // create a TeeOutputStream to write to both the
                        // response output stream and the cache simultaneously.
                        cacheWritableChannel = cache.getImageWritableChannel(this.ops);
                        TeeWritableByteChannel teeChannel = new TeeWritableByteChannel(
                                writableChannel, cacheWritableChannel);
                        doCacheAwareWrite(teeChannel, cache);
                    }
                } catch (Exception e) {
                    throw new IOException(e);
                } finally {
                    if (cacheWritableChannel != null &&
                            cacheWritableChannel.isOpen()) {
                        cacheWritableChannel.close();
                    }
                }
            } else {
                doWrite(writableChannel);
            }
        } finally {
            try {
                if (readableChannel != null && readableChannel.isOpen()) {
                    readableChannel.close();
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * Variant of doWrite() that cleans up incomplete cached images when
     * the connection has been broken.
     *
     * @param writableChannel
     * @param cache
     * @throws IOException
     */
    private void doCacheAwareWrite(TeeWritableByteChannel writableChannel,
                                   Cache cache) throws IOException {
        try {
            doWrite(writableChannel);
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            cache.purge(this.ops);
        }
    }

    private void doWrite(WritableByteChannel writableChannel) throws IOException {
        try {
            final long msec = System.currentTimeMillis();
            // If the operations are effectively a no-op, the source image can
            // be streamed directly.
            if (this.ops.isNoOp(this.sourceFormat)) {
                if (this.file != null) {
                    IOUtils.copy(new FileInputStream(this.file).getChannel(),
                            writableChannel);
                } else {
                    IOUtils.copy(readableChannel, writableChannel);
                }
                logger.debug("Streamed with no processing in {} msec",
                        System.currentTimeMillis() - msec);
            } else {
                Processor proc = ProcessorFactory.
                        getProcessor(this.sourceFormat);
                if (this.file != null) {
                    FileProcessor fproc = (FileProcessor) proc;
                    fproc.process(this.ops, this.sourceFormat, this.fullSize,
                            this.file, writableChannel);
                } else {
                    ChannelProcessor sproc = (ChannelProcessor) proc;
                    sproc.process(this.ops, this.sourceFormat,
                            this.fullSize, readableChannel, writableChannel);
                }
                logger.debug("{} processed in {} msec",
                        proc.getClass().getSimpleName(),
                        System.currentTimeMillis() - msec);
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

}