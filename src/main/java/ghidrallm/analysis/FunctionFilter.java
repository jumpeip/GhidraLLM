package ghidrallm.analysis;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import ghidra.program.model.listing.*;
import ghidra.util.task.TaskMonitor;

/**
 * Selects functions by name. Patterns are comma/whitespace separated globs ({@code FUN_*},
 * {@code sub_4010??}) or regular expressions; a leading {@code !} excludes matches.
 */
public class FunctionFilter {

	private final List<Pattern> include = new ArrayList<>();
	private final List<Pattern> exclude = new ArrayList<>();
	public boolean skipThunks = true;
	public boolean skipExternal = true;
	public long minBodyBytes = 0;

	public FunctionFilter(String patterns, boolean regex, boolean caseSensitive)
			throws PatternSyntaxException {
		int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
		String[] parts = regex ? patterns.split("\\s*\\n\\s*|\\s*,\\s*(?![^{]*})")
				: patterns.split("[,\\s]+");
		for (String raw : parts) {
			String p = raw.trim();
			if (p.isEmpty()) {
				continue;
			}
			boolean neg = p.startsWith("!");
			if (neg) {
				p = p.substring(1).trim();
			}
			Pattern compiled = Pattern.compile(regex ? p : globToRegex(p), flags);
			(neg ? exclude : include).add(compiled);
		}
	}

	public static String globToRegex(String glob) {
		StringBuilder sb = new StringBuilder("^");
		for (char c : glob.toCharArray()) {
			switch (c) {
				case '*' -> sb.append(".*");
				case '?' -> sb.append('.');
				default -> sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return sb.append('$').toString();
	}

	public boolean matchesName(String name) {
		boolean in = include.isEmpty();
		for (Pattern p : include) {
			if (p.matcher(name).find()) {
				in = true;
				break;
			}
		}
		if (!in) {
			return false;
		}
		for (Pattern p : exclude) {
			if (p.matcher(name).find()) {
				return false;
			}
		}
		return true;
	}

	public boolean matches(Function f) {
		if (skipExternal && f.isExternal()) {
			return false;
		}
		if (skipThunks && f.isThunk()) {
			return false;
		}
		if (minBodyBytes > 0 && f.getBody().getNumAddresses() < minBodyBytes) {
			return false;
		}
		return matchesName(f.getName());
	}

	public List<Function> apply(Program program, TaskMonitor monitor) {
		List<Function> out = new ArrayList<>();
		FunctionIterator it = program.getFunctionManager().getFunctions(true);
		while (it.hasNext()) {
			if (monitor != null && monitor.isCancelled()) {
				break;
			}
			Function f = it.next();
			if (matches(f)) {
				out.add(f);
			}
		}
		return out;
	}

	/**
	 * Orders functions so callees come before their callers (post-order over the call graph,
	 * restricted to the given set). Iterative so deep graphs can't overflow the stack.
	 */
	public static List<Function> bottomUp(List<Function> functions, TaskMonitor monitor) {
		Set<Function> wanted = new HashSet<>(functions);
		Set<Function> visited = new HashSet<>();
		List<Function> order = new ArrayList<>(functions.size());
		for (Function root : functions) {
			if (visited.contains(root)) {
				continue;
			}
			Deque<Object[]> stack = new ArrayDeque<>();
			visited.add(root);
			stack.push(new Object[] { root, children(root, wanted, monitor).iterator() });
			while (!stack.isEmpty()) {
				if (monitor != null && monitor.isCancelled()) {
					return functions;
				}
				Object[] top = stack.peek();
				@SuppressWarnings("unchecked")
				Iterator<Function> kids = (Iterator<Function>) top[1];
				if (kids.hasNext()) {
					Function k = kids.next();
					if (visited.add(k)) {
						stack.push(new Object[] { k, children(k, wanted, monitor).iterator() });
					}
				}
				else {
					stack.pop();
					order.add((Function) top[0]);
				}
			}
		}
		return order;
	}

	private static List<Function> children(Function f, Set<Function> wanted, TaskMonitor m) {
		List<Function> l = new ArrayList<>();
		for (Function c : f.getCalledFunctions(m)) {
			if (wanted.contains(c)) {
				l.add(c);
			}
		}
		l.sort(Comparator.comparing(Function::getEntryPoint));
		return l;
	}
}
