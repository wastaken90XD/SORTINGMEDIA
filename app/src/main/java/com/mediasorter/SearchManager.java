package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Query parser and matcher shared by file search, tag-bar search, and tag
 * conditions. A QueryParser is immutable and can be built once per query
 * change, then reused for every file/tag in a background pass.
 */
public class SearchManager {

    private volatile List<MediaFile> fullList = new ArrayList<MediaFile>();
    private String cachedTagQuery;
    private QueryParser cachedTagParser;

    public static class InvalidQueryException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public InvalidQueryException(String message) { super(message); }
    }

    public void setFullList(List<MediaFile> files) {
        this.fullList = files == null ? new ArrayList<MediaFile>() : new ArrayList<MediaFile>(files);
    }

    /** Builds one immutable parser for a query. */
    public QueryParser parseQuery(String query) {
        return QueryParser.parse(query);
    }

    public List<MediaFile> search(String query) {
        return search(parseQuery(query));
    }

    public List<MediaFile> search(QueryParser parser) {
        List<MediaFile> result = new ArrayList<MediaFile>();
        if (parser == null) return new ArrayList<MediaFile>(fullList);
        List<MediaFile> snapshot = fullList;
        for (MediaFile file : snapshot) {
            if (file != null && parser.matchesFile(file)) result.add(file);
        }
        return result;
    }

    /** Compatibility API used by GroupManager; parsing happens once per call. */
    public boolean matchesTagPattern(String tag, String query) {
        if (tag == null || query == null || query.trim().isEmpty()) return false;
        QueryParser parser;
        synchronized (this) {
            if (!query.equals(cachedTagQuery)) {
                cachedTagQuery = query;
                cachedTagParser = parseQuery(query);
            }
            parser = cachedTagParser;
        }
        return parser != null && parser.matchesText(tag);
    }

    public static final class QueryParser {
        private final Node root;

        private QueryParser(Node parsedRoot) {
            root = parsedRoot;
        }

        public static QueryParser parse(String query) {
            if (query == null || query.trim().isEmpty()) {
                return new QueryParser(new MatchAllNode());
            }
            Lexer lexer = new Lexer(query);
            List<Token> tokens = lexer.lex();
            Parser parser = new Parser(tokens);
            Node root = parser.parseExpression();
            if (parser.hasNext()) throw new InvalidQueryException("Unexpected token");
            return new QueryParser(root);
        }

        public boolean matchesFile(MediaFile file) {
            return file != null && root.matchesFile(file);
        }

        public boolean matchesText(String text) {
            return root.matchesText(text == null ? "" : text);
        }

        public boolean matchesTags(List<String> tags) {
            return root.matchesTags(tags == null ? new ArrayList<String>() : tags);
        }
    }

    private interface Node {
        boolean matchesFile(MediaFile file);
        boolean matchesText(String text);
        boolean matchesTags(List<String> tags);
    }

    private static final class MatchAllNode implements Node {
        @Override public boolean matchesFile(MediaFile file) { return true; }
        @Override public boolean matchesText(String text) { return true; }
        @Override public boolean matchesTags(List<String> tags) { return true; }
    }

    private static final class NotNode implements Node {
        private final Node child;
        NotNode(Node value) { child = value; }
        @Override public boolean matchesFile(MediaFile file) { return !child.matchesFile(file); }
        @Override public boolean matchesText(String text) { return !child.matchesText(text); }
        @Override public boolean matchesTags(List<String> tags) { return !child.matchesTags(tags); }
    }

    private static final class AndNode implements Node {
        private final Node left;
        private final Node right;
        AndNode(Node a, Node b) { left = a; right = b; }
        @Override public boolean matchesFile(MediaFile file) {
            return left.matchesFile(file) && right.matchesFile(file);
        }
        @Override public boolean matchesText(String text) {
            return left.matchesText(text) && right.matchesText(text);
        }
        @Override public boolean matchesTags(List<String> tags) {
            return left.matchesTags(tags) && right.matchesTags(tags);
        }
    }

    private static final class OrNode implements Node {
        private final Node left;
        private final Node right;
        OrNode(Node a, Node b) { left = a; right = b; }
        @Override public boolean matchesFile(MediaFile file) {
            return left.matchesFile(file) || right.matchesFile(file);
        }
        @Override public boolean matchesText(String text) {
            return left.matchesText(text) || right.matchesText(text);
        }
        @Override public boolean matchesTags(List<String> tags) {
            return left.matchesTags(tags) || right.matchesTags(tags);
        }
    }

    private static final class TermNode implements Node {
        private enum Kind { GENERIC, TAG, EXACT, STARTS, ENDS, TYPE, EXT, SIZE, WIDTH, HEIGHT, TAGGED, UNTAGGED }
        private final String raw;
        private final String value;
        private final Kind kind;
        private final Pattern regex;
        private final boolean wildcard;

        TermNode(String token) {
            raw = token;
            if (token.length() >= 2 && token.charAt(0) == '/' && token.charAt(token.length() - 1) == '/') {
                String expression = token.substring(1, token.length() - 1).replace("\\/", "/");
                try {
                    regex = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
                } catch (Exception error) {
                    throw new InvalidQueryException("Invalid pattern");
                }
                value = expression;
                kind = Kind.GENERIC;
                wildcard = false;
                return;
            }
            regex = null;
            String lower = token.toLowerCase(Locale.US);
            int colon = lower.indexOf(':');
            String field = colon > 0 ? lower.substring(0, colon) : "";
            String body = colon > 0 ? token.substring(colon + 1) : token;
            if ("tag".equals(field)) kind = Kind.TAG;
            else if ("exact".equals(field)) kind = Kind.EXACT;
            else if ("starts".equals(field)) kind = Kind.STARTS;
            else if ("ends".equals(field)) kind = Kind.ENDS;
            else if ("type".equals(field)) kind = Kind.TYPE;
            else if ("ext".equals(field)) kind = Kind.EXT;
            else if ("size".equals(field)) kind = Kind.SIZE;
            else if ("width".equals(field)) kind = Kind.WIDTH;
            else if ("height".equals(field)) kind = Kind.HEIGHT;
            else if ("tagged".equals(lower)) kind = Kind.TAGGED;
            else if ("untagged".equals(lower)) kind = Kind.UNTAGGED;
            else kind = Kind.GENERIC;
            value = body;
            wildcard = body.indexOf('*') >= 0 || body.indexOf('?') >= 0;
        }

        @Override public boolean matchesFile(MediaFile file) {
            if (file == null) return false;
            switch (kind) {
                case TAGGED: return !file.getTags().isEmpty();
                case UNTAGGED: return file.getTags().isEmpty();
                case TYPE: return matchesValue(file.getType().name());
                case EXT: return matchesValue(extension(file.getName()));
                case SIZE: return matchesSize(file.getSize(), value);
                case WIDTH: return matchesDimension(file.getWidth(), value);
                case HEIGHT: return matchesDimension(file.getHeight(), value);
                case TAG:
                    for (String tag : file.getTags()) if (matchesValue(tag)) return true;
                    return false;
                case EXACT:
                case STARTS:
                case ENDS:
                case GENERIC:
                default:
                    if (matchesValue(file.getName()) || matchesValue(file.getPath())) return true;
                    for (String tag : file.getTags()) if (matchesValue(tag)) return true;
                    return false;
            }
        }

        @Override public boolean matchesText(String text) {
            switch (kind) {
                case TAGGED: return true;
                case UNTAGGED: return false;
                case TYPE:
                case EXT:
                case SIZE:
                case WIDTH:
                case HEIGHT: return false;
                default: return matchesValue(text);
            }
        }

        @Override public boolean matchesTags(List<String> tags) {
            if (kind == Kind.TAGGED) return !tags.isEmpty();
            if (kind == Kind.UNTAGGED) return tags.isEmpty();
            if (kind == Kind.TYPE || kind == Kind.EXT || kind == Kind.SIZE
                    || kind == Kind.WIDTH || kind == Kind.HEIGHT) return false;
            for (String tag : tags) if (matchesValue(tag)) return true;
            return false;
        }

        private boolean matchesValue(String candidate) {
            if (candidate == null) return false;
            if (regex != null) return regex.matcher(candidate).find();
            String target = candidate.toLowerCase(Locale.US);
            String wanted = value.toLowerCase(Locale.US);
            if (kind == Kind.EXACT && !wildcard) return target.equals(wanted);
            if (kind == Kind.STARTS && !wildcard) return target.startsWith(wanted);
            if (kind == Kind.ENDS && !wildcard) return target.endsWith(wanted);
            if (!wildcard) return target.contains(wanted);
            String expression = wildcardExpression(wanted);
            try { return Pattern.compile("^" + expression + "$", Pattern.CASE_INSENSITIVE)
                    .matcher(candidate).find(); }
            catch (Exception error) { throw new InvalidQueryException("Invalid pattern"); }
        }

        private String wildcardExpression(String text) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '*') result.append(".*");
                else if (c == '?') result.append('.');
                else result.append(Pattern.quote(String.valueOf(c)));
            }
            return result.toString();
        }

        private boolean matchesSize(long actual, String expression) {
            try {
                String value = expression.trim().toLowerCase(Locale.US);
                int operator = 0;
                if (value.startsWith(">=") || value.startsWith("<=")) operator = 2;
                else if (value.startsWith(">") || value.startsWith("<") || value.startsWith("=")) operator = 1;
                String number = operator == 0 ? value : value.substring(operator == 2 ? 2 : 1).trim();
                long expected = parseSize(number);
                if (value.startsWith(">=")) return actual >= expected;
                if (value.startsWith("<=")) return actual <= expected;
                if (value.startsWith(">")) return actual > expected;
                if (value.startsWith("<")) return actual < expected;
                return actual == expected;
            } catch (Exception ignored) { return false; }
        }

        private long parseSize(String text) {
            if (text.endsWith("mb")) return Long.parseLong(text.substring(0, text.length() - 2).trim()) * 1024L * 1024L;
            if (text.endsWith("kb")) return Long.parseLong(text.substring(0, text.length() - 2).trim()) * 1024L;
            if (text.endsWith("gb")) return Long.parseLong(text.substring(0, text.length() - 2).trim()) * 1024L * 1024L * 1024L;
            return Long.parseLong(text);
        }

        private boolean matchesDimension(int actual, String expression) {
            try {
                String value = expression.trim();
                if (value.startsWith(">=")) return actual >= Integer.parseInt(value.substring(2).trim());
                if (value.startsWith("<=")) return actual <= Integer.parseInt(value.substring(2).trim());
                if (value.startsWith(">")) return actual > Integer.parseInt(value.substring(1).trim());
                if (value.startsWith("<")) return actual < Integer.parseInt(value.substring(1).trim());
                return actual == Integer.parseInt(value);
            } catch (Exception ignored) { return false; }
        }

        private String extension(String name) {
            if (name == null) return "";
            int dot = name.lastIndexOf('.');
            return dot < 0 ? "" : name.substring(dot + 1);
        }
    }

    private enum TokenType { TERM, AND, OR, NOT, LEFT, RIGHT }

    private static final class Token {
        final TokenType type;
        final String text;
        Token(TokenType tokenType, String tokenText) { type = tokenType; text = tokenText; }
    }

    private static final class Lexer {
        private final String source;
        Lexer(String value) { source = value; }

        List<Token> lex() {
            List<Token> result = new ArrayList<Token>();
            int index = 0;
            while (index < source.length()) {
                char c = source.charAt(index);
                if (Character.isWhitespace(c)) { index++; continue; }
                if (c == '(') { result.add(new Token(TokenType.LEFT, "(")); index++; continue; }
                if (c == ')') { result.add(new Token(TokenType.RIGHT, ")")); index++; continue; }
                if (c == '"') {
                    int end = ++index;
                    StringBuilder quoted = new StringBuilder();
                    boolean closed = false;
                    while (end < source.length()) {
                        char q = source.charAt(end++);
                        if (q == '"') { closed = true; break; }
                        if (q == '\\' && end < source.length()) q = source.charAt(end++);
                        quoted.append(q);
                    }
                    if (!closed) throw new InvalidQueryException("Invalid pattern");
                    result.add(new Token(TokenType.TERM, quoted.toString()));
                    index = end;
                    continue;
                }
                if (c == '/') {
                    int end = index + 1;
                    boolean escaped = false;
                    while (end < source.length()) {
                        char q = source.charAt(end++);
                        if (q == '/' && !escaped) break;
                        if (q == '\\' && !escaped) escaped = true;
                        else escaped = false;
                    }
                    if (end <= index + 1 || end > source.length()
                            || source.charAt(end - 1) != '/') {
                        throw new InvalidQueryException("Invalid pattern");
                    }
                    result.add(new Token(TokenType.TERM, source.substring(index, end)));
                    index = end;
                    continue;
                }
                int end = index;
                while (end < source.length() && !Character.isWhitespace(source.charAt(end))
                        && source.charAt(end) != '(' && source.charAt(end) != ')') end++;
                String word = source.substring(index, end);
                if ("AND".equalsIgnoreCase(word) || "&&".equals(word)) {
                    result.add(new Token(TokenType.AND, word));
                } else if ("OR".equalsIgnoreCase(word) || "||".equals(word)) {
                    result.add(new Token(TokenType.OR, word));
                } else if ("NOT".equalsIgnoreCase(word) || "!".equals(word)) {
                    result.add(new Token(TokenType.NOT, word));
                } else if (word.startsWith("-") && word.length() > 1) {
                    result.add(new Token(TokenType.NOT, "NOT"));
                    result.add(new Token(TokenType.TERM, word.substring(1)));
                } else {
                    result.add(new Token(TokenType.TERM, word));
                }
                index = end;
            }
            return result;
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int index;
        Parser(List<Token> values) { tokens = values; }
        boolean hasNext() { return index < tokens.size(); }
        Node parseExpression() {
            if (tokens.isEmpty()) throw new InvalidQueryException("Invalid pattern");
            return parseOr();
        }
        private Node parseOr() {
            Node left = parseAnd();
            while (hasNext() && tokens.get(index).type == TokenType.OR) {
                index++;
                left = new OrNode(left, parseAnd());
            }
            return left;
        }
        private Node parseAnd() {
            Node left = parseUnary();
            while (hasNext()) {
                TokenType type = tokens.get(index).type;
                if (type == TokenType.AND) {
                    index++;
                    left = new AndNode(left, parseUnary());
                } else if (type == TokenType.TERM || type == TokenType.NOT || type == TokenType.LEFT) {
                    left = new AndNode(left, parseUnary());
                } else break;
            }
            return left;
        }
        private Node parseUnary() {
            if (hasNext() && tokens.get(index).type == TokenType.NOT) {
                index++;
                return new NotNode(parseUnary());
            }
            return parsePrimary();
        }
        private Node parsePrimary() {
            if (!hasNext()) throw new InvalidQueryException("Invalid pattern");
            Token token = tokens.get(index++);
            if (token.type == TokenType.TERM) return new TermNode(token.text);
            if (token.type == TokenType.LEFT) {
                Node nested = parseOr();
                if (!hasNext() || tokens.get(index).type != TokenType.RIGHT) {
                    throw new InvalidQueryException("Invalid pattern");
                }
                index++;
                return nested;
            }
            throw new InvalidQueryException("Invalid pattern");
        }
    }
}
