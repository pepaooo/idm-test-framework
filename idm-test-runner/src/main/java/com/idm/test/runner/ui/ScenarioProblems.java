package com.idm.test.runner.ui;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.ValidationException;
import com.idm.test.engine.spi.Message;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads and validates one scenario's text the way {@code idmtest validate} does
 * ({@link ScenarioLoader}, then {@link ScenarioValidator}) and turns the first problem into a
 * {@link Problem} with a position: the validator reports by step, so the line comes from the YAML —
 * the parameter's own line when the problem names one (an unknown parameter, {@code wait},
 * {@code as}, {@code description}), else the step's line, a fixture's, a top-level key's, or the
 * parser's position for a YAML error; line 1 when nothing better is known.
 */
public final class ScenarioProblems {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ScenarioProblems() {
    }

    /** No problem, or the first one; the text is the framed message {@code idmtest validate} prints. */
    public static Optional<Problem> check(String yaml, String sourceName, ScenarioValidator validator) {
        try {
            validator.validate(ScenarioLoader.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), sourceName), sourceName);
            return Optional.empty();
        } catch (ValidationException e) {
            return Optional.of(problem(e, yaml));
        }
    }

    private static Problem problem(ValidationException e, String yaml) {
        Message framed = e.detail();
        if (framed == null) {
            // the loader and validator always give a keyed message; plain text is shown as it is
            return error(Position.START, "engine.text", Message.of("engine.text", e.getMessage()));
        }
        Message problem = framed.args().getLast() instanceof Message inner ? inner : framed;
        if ("validation.load.parse".equals(problem.key())) {
            return parseError(e, String.valueOf(framed.args().getFirst()), yaml);
        }
        Locations locations = Locations.of(yaml);
        Position position = switch (framed.key()) {
            case "validation.frame.step" -> stepPosition(locations, "steps", framed, problem);
            case "validation.frame.cleanup" -> stepPosition(locations, "cleanup", framed, problem);
            default -> filePosition(locations, problem);
        };
        return error(position, problem.key(), framed);
    }

    private static Problem error(Position position, String key, Message message) {
        return new Problem(Problem.Severity.ERROR, position.line(), position.column(), key, message);
    }

    private static Position stepPosition(Locations locations, String section, Message framed, Message problem) {
        int index = number(framed.args().get(1)) - 1;
        String param = switch (problem.key()) {
            case "validation.step.unknownParam", "validation.cleanup.reserved" -> text(problem, 0);
            case "validation.step.invalidWait", "validation.step.waitOnAction" -> "wait";
            case "validation.step.asInvalid" -> "as";
            case "validation.binding.helperName", "validation.binding.shadows" ->
                    "as:".equals(text(problem, 0)) ? "as" : text(problem, 1);
            case "validation.description.notText", "validation.description.expression" -> "description";
            default -> null;
        };
        String step = section + "[" + index + "]";
        return locations.find(param == null ? null : step + "." + param)
                .or(() -> locations.find(step))
                .or(() -> locations.find(section))
                .orElse(Position.START);
    }

    private static Position filePosition(Locations locations, Message problem) {
        String path = switch (problem.key()) {
            case "validation.fixture.helperName", "validation.fixture.unknownGenerator",
                 "validation.load.fixtureNotMap", "validation.load.fixtureValuesNotMap" ->
                    "fixtures." + text(problem, 0);
            case "validation.load.fixturesNotMap" -> "fixtures";
            case "validation.noSteps" -> "steps";
            case "validation.load.sectionNotList" -> text(problem, 0);
            case "validation.load.stepShape" -> "steps[" + (number(problem.args().getFirst()) - 1) + "]";
            case "validation.load.cleanupShape" -> "cleanup[" + (number(problem.args().getFirst()) - 1) + "]";
            case "validation.load.nameRequired" -> "name";
            default -> null;
        };
        return locations.find(path).orElse(Position.START);
    }

    /**
     * A YAML syntax error, at the YAML parser's problem mark, with the parser's description only:
     * the parser's own message quotes the offending line of the file (which may hold a password),
     * so it is left out.
     */
    private static Problem parseError(ValidationException e, String sourceName, String yaml) {
        String description = null;
        Position position = null;
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof MarkedYamlEngineException marked) {
                description = Stream.of(marked.getContext(), marked.getProblem())
                        .filter(text -> text != null && !text.isBlank())
                        .map(String::strip)
                        .collect(Collectors.joining(": "));
                position = marked.getProblemMark().or(marked::getContextMark)
                        .map(mark -> new Position(mark.getLine() + 1, mark.getColumn() + 1))
                        .orElse(null);
                break;
            }
            if (cause instanceof JacksonException jackson && description == null) {
                description = jackson.getOriginalMessage();
                TokenStreamLocation location = jackson.getLocation();
                if (location != null && location.getLineNr() > 0) {
                    position = new Position(location.getLineNr(), Math.max(1, location.getColumnNr()));
                }
            }
        }
        if (description == null || description.isBlank()) {
            description = e.getCause() != null ? e.getCause().getClass().getSimpleName() : "?";
        }
        int lines = Math.max(1, (int) yaml.lines().count());
        Position at = position == null ? Position.START
                : position.line() > lines ? new Position(lines, 1) : position;
        Message text = Message.of("validation.frame.file", sourceName,
                Message.of("validation.load.parse", firstLine(description)));
        return error(at, "validation.load.parse", text);
    }

    /** Jackson's own messages may carry a source excerpt on later lines. */
    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return (end >= 0 ? text.substring(0, end) : text).strip();
    }

    private static String text(Message message, int index) {
        return index < message.args().size() ? String.valueOf(message.args().get(index)) : null;
    }

    private static int number(Object value) {
        try {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A 1-based line and column. */
    record Position(int line, int column) {
        static final Position START = new Position(1, 1);
    }

    /**
     * Where things are in the YAML text, by a small path: {@code name}, {@code steps},
     * {@code fixtures.user}, {@code steps[0]} (the step type's key), {@code steps[0].dn} (a
     * parameter's key), and the same for {@code cleanup}. What a broken file has before the error.
     */
    record Locations(Map<String, Position> byPath) {

        Optional<Position> find(String path) {
            return path == null ? Optional.empty() : Optional.ofNullable(byPath.get(path));
        }

        static Locations of(String yaml) {
            Map<String, Position> found = new HashMap<>();
            try (JsonParser parser = YAML.createParser(yaml)) {
                if (parser.nextToken() == JsonToken.START_OBJECT) {
                    readRoot(parser, found);
                }
            } catch (RuntimeException broken) {
                // keep what was found before the error
            }
            return new Locations(Map.copyOf(found));
        }

        private static void readRoot(JsonParser parser, Map<String, Position> found) {
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String key = parser.currentName();
                found.putIfAbsent(key, at(parser.currentTokenLocation()));
                JsonToken value = parser.nextToken();
                if (("steps".equals(key) || "cleanup".equals(key)) && value == JsonToken.START_ARRAY) {
                    readSection(parser, key, found);
                } else if ("fixtures".equals(key) && value == JsonToken.START_OBJECT) {
                    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        found.putIfAbsent("fixtures." + parser.currentName(), at(parser.currentTokenLocation()));
                        parser.nextToken();
                        parser.skipChildren();
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }

        private static void readSection(JsonParser parser, String section, Map<String, Position> found) {
            int index = 0;
            JsonToken item;
            while ((item = parser.nextToken()) != JsonToken.END_ARRAY && item != null) {
                String step = section + "[" + index++ + "]";
                found.put(step, at(parser.currentTokenLocation()));
                if (item != JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    continue;
                }
                boolean first = true;
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    if (first) {
                        found.put(step, at(parser.currentTokenLocation()));
                    }
                    JsonToken params = parser.nextToken();
                    if (first && params == JsonToken.START_OBJECT) {
                        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                            found.putIfAbsent(step + "." + parser.currentName(), at(parser.currentTokenLocation()));
                            parser.nextToken();
                            parser.skipChildren();
                        }
                    } else {
                        parser.skipChildren();
                    }
                    first = false;
                }
            }
        }

        private static Position at(TokenStreamLocation location) {
            return new Position(Math.max(1, location.getLineNr()), Math.max(1, location.getColumnNr()));
        }
    }
}
