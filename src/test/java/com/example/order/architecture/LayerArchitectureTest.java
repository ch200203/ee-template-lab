package com.example.order.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * D3 §5의 ARCH-01~05 구조 검사. 위반을 D5 §2.6 보고서로 먼저 기록한 뒤 테스트를 실패시킨다.
 */
class LayerArchitectureTest {

    private static final String ROOT = "com.example.order";
    private static final Pattern REPO = Pattern.compile("^[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9._-]*$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern REMOTE_TAIL = Pattern.compile("[:/]([^/:]+)/([^/]+?)(?:\\.git)?/?$");

    enum Layer {
        API("api 계층", "은", "을"),
        APPLICATION("application 계층", "은", "을"),
        DOMAIN("domain 계층", "은", "을"),
        INFRA("infra 계층", "은", "을"),
        COMMON("common 패키지", "는", "를"),
        BOOTSTRAP("부트스트랩", "은", "을"),
        OUTSIDE("허용 밖 하위 패키지", "는", "를");

        final String label;
        final String topic;
        final String object;

        Layer(String label, String topic, String object) {
            this.label = label;
            this.topic = topic;
            this.object = object;
        }
    }

    record DependencyRule(String id, Layer source, Set<Layer> forbidden, String fix) {}

    record Violation(String ruleId, String path, Integer line, String message) {}

    private static final List<DependencyRule> DEPENDENCY_RULES = List.of(
            new DependencyRule("ARCH-01", Layer.API,
                    EnumSet.of(Layer.INFRA, Layer.DOMAIN, Layer.BOOTSTRAP),
                    "Service를 거쳐 호출하세요."),
            new DependencyRule("ARCH-02", Layer.APPLICATION,
                    EnumSet.of(Layer.API, Layer.BOOTSTRAP),
                    "요청 변환을 api에 두세요."),
            new DependencyRule("ARCH-03", Layer.DOMAIN,
                    EnumSet.of(Layer.API, Layer.APPLICATION, Layer.INFRA, Layer.BOOTSTRAP),
                    "도메인 로직을 독립시키세요."),
            new DependencyRule("ARCH-04", Layer.INFRA,
                    EnumSet.of(Layer.API, Layer.APPLICATION, Layer.BOOTSTRAP),
                    "인프라 구현을 domain 계약에 의존시키세요."),
            new DependencyRule("ARCH-05", Layer.COMMON,
                    EnumSet.of(Layer.API, Layer.APPLICATION, Layer.DOMAIN, Layer.INFRA, Layer.BOOTSTRAP),
                    "클래스를 네 계층·common·루트 중 하나로 옮기고 common은 계층에 의존하지 않게 하세요."));

    private static final String PACKAGE_FIX =
            "클래스를 네 계층·common·루트 중 하나로 옮기고 common은 계층에 의존하지 않게 하세요.";

    @Test
    void layersFollowAllowedDirections() throws IOException {
        Map<String, Object> metadata = runMetadata();
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);

        Set<Violation> violations = new LinkedHashSet<>();
        boolean archUnitViolation = false;
        for (DependencyRule rule : DEPENDENCY_RULES) {
            EvaluationResult result = dependencyRule(rule, violations).evaluate(classes);
            archUnitViolation |= result.hasViolation();
        }
        archUnitViolation |= packageRule(violations).evaluate(classes).hasViolation();

        writeReport(metadata, violations);

        if (archUnitViolation || !violations.isEmpty()) {
            StringBuilder message = new StringBuilder("구조 검사 위반 ")
                    .append(violations.size()).append("건\n");
            sorted(violations).forEach(v -> message.append(v.message()).append('\n'));
            fail(message.toString());
        }
    }

    private static ArchRule dependencyRule(DependencyRule rule, Set<Violation> sink) {
        ArchCondition<JavaClass> condition = new ArchCondition<>("not depend on " + rule.forbidden()) {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                List<Dependency> forbidden = origin.getDirectDependenciesFromSelf().stream()
                        .filter(d -> {
                            Layer target = layerOf(d.getTargetClass().getBaseComponentType());
                            return target != null && rule.forbidden().contains(target);
                        })
                        .toList();
                // 필드 타입처럼 줄 번호가 없는 의존은 같은 대상의 줄 있는 위반이 없을 때만 보고한다.
                Set<String> locatedTargets = new LinkedHashSet<>();
                for (Dependency d : forbidden) {
                    if (d.getSourceCodeLocation().getLineNumber() > 0) {
                        locatedTargets.add(d.getTargetClass().getBaseComponentType().getName());
                    }
                }
                for (Dependency dependency : forbidden) {
                    JavaClass targetClass = dependency.getTargetClass().getBaseComponentType();
                    Layer target = layerOf(targetClass);
                    int lineNumber = dependency.getSourceCodeLocation().getLineNumber();
                    if (lineNumber <= 0 && locatedTargets.contains(targetClass.getName())) {
                        continue;
                    }
                    String path = sourcePath(origin);
                    Integer line = lineNumber > 0 ? lineNumber : null;
                    String text = rule.source().label + rule.source().topic + " " + target.label
                            + target.object + " 직접 부를 수 없습니다.";
                    Violation violation = new Violation(rule.id(), path, line,
                            message(rule.id(), text, path, line, rule.fix()));
                    sink.add(violation);
                    events.add(SimpleConditionEvent.violated(dependency, violation.message()));
                }
            }
        };
        return ArchRuleDefinition.classes()
                .that(inLayer(rule.source()))
                .should(condition)
                .as(rule.id() + " " + rule.source().label + " 의존 방향")
                .because("D3 §5의 허용 의존 방향을 지킨다")
                .allowEmptyShould(true);
    }

    private static ArchRule packageRule(Set<Violation> sink) {
        ArchCondition<JavaClass> condition = new ArchCondition<>("not exist") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String path = sourcePath(item);
                String text = "허용되지 않은 하위 패키지 " + item.getPackageName() + "에 클래스를 둘 수 없습니다.";
                Violation violation = new Violation("ARCH-05", path, null,
                        message("ARCH-05", text, path, null, PACKAGE_FIX));
                sink.add(violation);
                events.add(SimpleConditionEvent.violated(item, violation.message()));
            }
        };
        return ArchRuleDefinition.classes()
                .that(inLayer(Layer.OUTSIDE))
                .should(condition)
                .as("ARCH-05 패키지 경계")
                .because("네 계층과 common 밖의 하위 패키지는 계층 우회 경로가 된다")
                .allowEmptyShould(true);
    }

    /** 위반 이벤트와 JSON 보고서가 함께 쓰는 유일한 메시지 조립 함수. */
    static String message(String ruleId, String text, String path, Integer line, String fix) {
        String location = line == null ? path : path + ":" + line;
        return "[" + ruleId + "] " + text + " (" + location + ") | 고치는 방법: " + fix;
    }

    static Layer layerOf(JavaClass javaClass) {
        String pkg = javaClass.getPackageName();
        if (pkg.equals(ROOT)) {
            return Layer.BOOTSTRAP;
        }
        if (!pkg.startsWith(ROOT + ".")) {
            return null;
        }
        String first = pkg.substring(ROOT.length() + 1).split("\\.", 2)[0];
        return switch (first) {
            case "api" -> Layer.API;
            case "application" -> Layer.APPLICATION;
            case "domain" -> Layer.DOMAIN;
            case "infra" -> Layer.INFRA;
            case "common" -> Layer.COMMON;
            default -> Layer.OUTSIDE;
        };
    }

    private static DescribedPredicate<JavaClass> inLayer(Layer layer) {
        return DescribedPredicate.describe("reside in " + layer.label, c -> layerOf(c) == layer);
    }

    private static String sourcePath(JavaClass javaClass) {
        JavaClass top = javaClass;
        while (top.getEnclosingClass().isPresent()) {
            top = top.getEnclosingClass().get();
        }
        String fileName = javaClass.getSource()
                .flatMap(Source::getFileName)
                .orElse(top.getSimpleName() + ".java");
        return "src/main/java/" + javaClass.getPackageName().replace('.', '/') + "/" + fileName;
    }

    private static List<Violation> sorted(Set<Violation> violations) {
        List<Violation> list = new ArrayList<>(violations);
        list.sort(Comparator.comparing(Violation::ruleId)
                .thenComparing(Violation::path)
                .thenComparing(v -> v.line() == null ? 0 : v.line()));
        return list;
    }

    private static void writeReport(Map<String, Object> metadata, Set<Violation> violations) throws IOException {
        Map<String, List<Violation>> byRule = new TreeMap<>();
        for (Violation v : sorted(violations)) {
            byRule.computeIfAbsent(v.ruleId(), k -> new ArrayList<>()).add(v);
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        byRule.forEach((ruleId, list) -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Violation v : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", v.path());
                item.put("line", v.line());
                item.put("message", v.message());
                items.add(item);
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("ruleId", ruleId);
            rule.put("count", items.size());
            rule.put("violations", items);
            rules.add(rule);
        });

        Map<String, Object> report = new LinkedHashMap<>(metadata);
        report.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        report.put("totalViolations", violations.size());
        report.put("rules", rules);

        Path target = Path.of(System.getProperty("ee.structureReport", "build/reports/ee/structure-report.json"));
        Files.createDirectories(target.toAbsolutePath().getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(target.toFile(), report);
    }

    /** D2 §3 표의 실행 메타데이터. CI 값이 하나라도 빠지면 보고서를 쓰지 않고 실패한다. */
    private static Map<String, Object> runMetadata() {
        String runId = env("EE_RUN_ID");
        String repo = env("EE_REPO");
        String prNumber = env("EE_PR_NUMBER");
        String headSha = env("EE_HEAD_SHA");
        String runAttempt = env("EE_RUN_ATTEMPT");
        boolean ci = runId != null;
        if (ci) {
            List<String> missing = new ArrayList<>();
            if (repo == null) missing.add("EE_REPO");
            if (prNumber == null) missing.add("EE_PR_NUMBER");
            if (headSha == null) missing.add("EE_HEAD_SHA");
            if (runAttempt == null) missing.add("EE_RUN_ATTEMPT");
            if (!missing.isEmpty()) {
                throw new IllegalStateException("EE_RUN_ID가 있으나 CI 실행 메타데이터가 없다: " + missing);
            }
        }
        if (repo == null) {
            repo = repoFromOrigin();
        }
        if (headSha == null) {
            headSha = git("rev-parse", "HEAD");
            if (headSha == null) {
                headSha = "0".repeat(40);
            }
        }
        repo = repo.toLowerCase(Locale.ROOT);
        if (!REPO.matcher(repo).matches()) {
            throw new IllegalStateException("repo 값이 owner/repo 형식이 아니다: " + repo);
        }
        if (!SHA.matcher(headSha).matches()) {
            throw new IllegalStateException("headSha 값이 40자리 소문자 SHA가 아니다: " + headSha);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("repo", repo);
        metadata.put("prNumber", prNumber == null ? null : Integer.valueOf(prNumber));
        metadata.put("headSha", headSha);
        metadata.put("runId", runId == null ? null : Long.valueOf(runId));
        metadata.put("runAttempt", runAttempt == null ? 1 : Integer.valueOf(runAttempt));
        metadata.put("scope", ci ? "ci" : "local");
        return metadata;
    }

    private static String repoFromOrigin() {
        String url = git("remote", "get-url", "origin");
        if (url != null) {
            Matcher m = REMOTE_TAIL.matcher(url);
            if (m.find()) {
                return m.group(1) + "/" + m.group(2);
            }
        }
        return "local/" + System.getProperty("ee.projectName", "project");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out;
            try (InputStream in = process.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0 || out.isEmpty()) {
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
