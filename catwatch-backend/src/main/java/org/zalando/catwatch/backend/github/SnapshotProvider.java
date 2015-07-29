package org.zalando.catwatch.backend.github;

import com.squareup.okhttp.Cache;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.OkHttpConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
public class SnapshotProvider {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotProvider.class);
    
    @Autowired
    private Scorer scorer;

    @Value("${cache.path}")
    private String cachePath;

    @Value("${cache.size}")
    private Integer cacheSize;

    @Value("${github.login}")
    private String login;

    @Value("${github.password}")
    private String password;

    private static final int MEGABYTE = 1024 * 1024;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    /**
     * OkHttpClient has to be shared between threads.
     *
     * @see <a href="https://github.com/square/okhttp/wiki/Recipes#response-caching">OkHttp Wiki</a>
     */
    private OkHttpClient httpClient;

    @PostConstruct
    public void init() {
        Optional<File> cacheDirectoryOptional = getCacheDirectory();
        if (cacheDirectoryOptional.isPresent()) {
            Cache cache = new Cache(cacheDirectoryOptional.get(), cacheSize * MEGABYTE);
            this.httpClient = new OkHttpClient().setCache(cache);
            logger.info("Initialized http client with {} mb cache.", cacheSize);
        } else {
            this.httpClient = new OkHttpClient();
            logger.warn("Initialized http client without cache.");
        }
    }

    public Future<Snapshot> takeSnapshot(String organizationName) throws IOException {
        GitHub gitHub = new GitHubBuilder()
                .withPassword(login, password)
                .withConnector(new OkHttpConnector(new OkUrlFactory(httpClient)))
                .build();
        return pool.submit(new TakeSnapshotTask(gitHub, organizationName, scorer));
    }
    
    private Optional<File> getCacheDirectory() {
        Path path = Paths.get(cachePath);

        if (Files.isDirectory(path)) {
            if (Files.isWritable(path)) {
                logger.info("Cache directory found.");
                return Optional.of(path.toFile());
            }
            logger.warn("Unable to write to cache directory '{}'.", cachePath);
            return Optional.empty();
        }

        logger.info("Cache directory '{}' is not found. Creating new directory.", cachePath);
        try {
            path = Files.createDirectories(path);
            logger.info("Cache directory created successfully.");
            return Optional.of(path.toFile());
        } catch (FileAlreadyExistsException e) {
            logger.warn("Failed to created cache directory: file already exists.");
        } catch (SecurityException e) {
            logger.warn("Failed to created cache directory: access denied.");
        } catch (IOException e) {
            logger.warn("Failed to created cache directory.", e);
        }
        return Optional.empty();
    }
}