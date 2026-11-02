package io.github.biapyx.fiji.env;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;


/**
 * Downloads the micromamba executable into the installation folder, where JDLL's {@code Mamba} (SAMJ) also puts it
 * ({@code <root>/bin/micromamba} or {@code <root>\Library\bin\micromamba.exe}).
 *
 * JDLL (and so SAMJ) only tries micro.mamba.pm with a 10 s timeout, which fails on slow networks or
 * where that site is blocked. Here GitHub releases (where micro.mamba.pm redirects to anyway) are tried
 * first, then micro.mamba.pm, with retries and generous timeouts.
 */
public class MicromambaInstaller {

	private static final int CONNECT_TIMEOUT_MS = 30_000;
	private static final int READ_TIMEOUT_MS = 60_000;
	private static final int ATTEMPTS_PER_SOURCE = 2;

	/** Progress of the download, from 0 to 1 (or -1 when the size is unknown). */
	public interface Progress {
		void update(double fraction, String message);
	}

	private MicromambaInstaller() {}

	public static File executable(File root) {
		return Platform.isWindows() ? new File(root, "Library" + File.separator + "bin" + File.separator + "micromamba.exe")
				: new File(root, "bin" + File.separator + "micromamba");
	}

	public static void install(File root, Progress progress, Consumer<String> log) throws IOException, InterruptedException {
		String platform = platform();
		File exe = executable(root);
		exe.getParentFile().mkdirs();
		List<String> errors = new ArrayList<>();

		// 1) Plain executable from GitHub releases
		String github = "https://github.com/mamba-org/micromamba-releases/releases/latest/download/micromamba-" + platform;
		// 2) Official redirector, which serves a .tar.bz2 archive
		String mambaPm = "https://micro.mamba.pm/api/micromamba/" + platform + "/latest";

		for (int attempt = 1; attempt <= ATTEMPTS_PER_SOURCE; attempt++) {
			try {
				File tmp = File.createTempFile("micromamba", Platform.isWindows() ? ".exe" : "");
				download(github, tmp, progress, log);
				Files.move(tmp.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING);
				finish(exe);
				return;
			} catch (IOException e) {
				errors.add(github + ": " + e.getMessage());
				log.accept("Download from GitHub failed (attempt " + attempt + "): " + e.getMessage());
			}
		}
		for (int attempt = 1; attempt <= ATTEMPTS_PER_SOURCE; attempt++) {
			try {
				File tmp = File.createTempFile("micromamba", ".tar.bz2");
				download(mambaPm, tmp, progress, log);
				extractFromTarBz2(tmp, exe);
				tmp.delete();
				finish(exe);
				return;
			} catch (IOException e) {
				errors.add(mambaPm + ": " + e.getMessage());
				log.accept("Download from micro.mamba.pm failed (attempt " + attempt + "): " + e.getMessage());
			}
		}
		throw new IOException("Could not download micromamba. Check your internet connection or proxy.\n"
				+ String.join("\n", errors));
	}

	private static void finish(File exe) throws IOException {
		if (!exe.setExecutable(true)) throw new IOException("Cannot make micromamba executable: " + exe);
	}

	private static void download(String url, File dest, Progress progress, Consumer<String> log)
			throws IOException, InterruptedException {
		log.accept("Downloading " + url);
		HttpURLConnection conn = open(url);
		long total = conn.getContentLengthLong();
		long done = 0;
		try (InputStream in = new BufferedInputStream(conn.getInputStream());
				OutputStream out = new FileOutputStream(dest)) {
			byte[] buf = new byte[64 * 1024];
			int n;
			long lastReport = 0;
			while ((n = in.read(buf)) != -1) {
				if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Download cancelled");
				out.write(buf, 0, n);
				done += n;
				if (done - lastReport > 256 * 1024) {
					lastReport = done;
					progress.update(total > 0 ? (double) done / total : -1, String.format("Downloading micromamba (%.1f MB)", done / 1e6));
				}
			}
		} finally {
			conn.disconnect();
		}
		if (total > 0 && done < total) throw new IOException("Incomplete download (" + done + " of " + total + " bytes)");
		progress.update(1, "Micromamba downloaded");
	}

	/** Opens the URL following redirects, also across http/https (HttpURLConnection does not). */
	private static HttpURLConnection open(String url) throws IOException {
		for (int i = 0; i < 10; i++) {
			HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setInstanceFollowRedirects(false);
			conn.setRequestProperty("User-Agent", "BiaPy-Fiji");
			int code = conn.getResponseCode();
			if (code >= 300 && code < 400) {
				String loc = conn.getHeaderField("Location");
				conn.disconnect();
				if (loc == null) throw new IOException("Redirect without location from " + url);
				url = new URL(new URL(url), loc).toString();
				continue;
			}
			if (code != 200) {
				conn.disconnect();
				throw new IOException("HTTP " + code + " from " + url);
			}
			return conn;
		}
		throw new IOException("Too many redirects: " + url);
	}

	private static void extractFromTarBz2(File archive, File exe) throws IOException {
		String wanted = Platform.isWindows() ? "Library/bin/micromamba.exe" : "bin/micromamba";
		try (TarArchiveInputStream tar = new TarArchiveInputStream(
				new BZip2CompressorInputStream(new BufferedInputStream(Files.newInputStream(archive.toPath()))))) {
			TarArchiveEntry e;
			while ((e = tar.getNextTarEntry()) != null) {
				if (e.getName().replace("\\", "/").endsWith(wanted)) {
					Files.copy(tar, exe.toPath(), StandardCopyOption.REPLACE_EXISTING);
					return;
				}
			}
		}
		throw new IOException("micromamba not found inside " + archive);
	}

	private static String platform() {
		String os = System.getProperty("os.name");
		String arch = System.getProperty("os.arch");
		boolean arm = arch.equals("aarch64") || arch.equals("arm64") || Platform.isRosetta();
		if (os.startsWith("Windows")) return "win-64";
		if (os.startsWith("Mac")) return arm ? "osx-arm64" : "osx-64";
		return arm ? "linux-aarch64" : "linux-64";
	}
}
