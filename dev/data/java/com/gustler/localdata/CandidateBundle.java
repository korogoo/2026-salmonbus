package com.gustler.localdata;

import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleInspector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;

/** 실제 관측으로 만든 dev 후보. 초기화용 8개 노선 번들과 운영 번들은 교체하지 않는다. */
final class CandidateBundle {
    static final String DIRECTORY = "coldstart-20261016";
    static final String DIGEST = "74edfef0d3ccc8d425a5ee257d60bfe5f14f3a28fad9cb4df91a1a1a391630c0";
    private static final Map<String, String> FILES = Map.of(
        "manifest.json", "35efa2bb01770649f13cf106aacf85701d91e5bcbc288a0b40597d0463c29d9b",
        "weights.safetensors", "7a7e8af51679df7665365d20a471bb80a89aab411063c73c00774dd2b0f2f719",
        "route-reference.json", "674e386686e944d18cf87b5772cf20af052c536110ffcccc3b3b4c88f608293d");

    static void prepare(Path directory) throws Exception {
        LocalData.require(!Files.isSymbolicLink(directory.getParent()) && !Files.isSymbolicLink(directory),
            "MODEL_DIRECTORY");
        Files.createDirectories(directory.getParent());
        Path stage = Files.createTempDirectory(directory.getParent(), ".candidate-");
        try {
            for (var entry : FILES.entrySet()) {
                try (var input = CandidateBundle.class.getResourceAsStream("/" + DIRECTORY + "/" + entry.getKey())) {
                    LocalData.require(input != null, "MODEL_CANDIDATE_MISSING");
                    byte[] bytes = input.readNBytes(10_485_761);
                    LocalData.require(ReferenceBundle.sha(bytes).equals(entry.getValue()), "MODEL_CANDIDATE_CHANGED");
                    Files.write(stage.resolve(entry.getKey()), bytes);
                    if (Files.exists(directory)) {
                        Path file = directory.resolve(entry.getKey());
                        LocalData.require(!Files.isSymbolicLink(file) && Files.isRegularFile(file)
                            && Files.size(file) == bytes.length && Arrays.equals(Files.readAllBytes(file), bytes),
                            "MODEL_CANDIDATE_CHANGED");
                    }
                }
            }
            LocalData.require(DIGEST.equals(new FileModelBundleInspector().inspect(stage.toString()).bundleDigest()),
                "MODEL_CANDIDATE_IDENTITY");
            if (!Files.exists(directory)) Files.move(stage, directory, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(stage)) {
                for (String name : FILES.keySet()) Files.deleteIfExists(stage.resolve(name));
                Files.delete(stage);
            }
        }
    }
}
