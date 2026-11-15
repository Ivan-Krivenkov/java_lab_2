import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"rawtypes", "unchecked"})
public class SimpleDBMS {

    public static void main(String[] args) {
        Store store = new Store("db_data");
        Engine engine = new Engine(store);
        engine.initDemoData();

        System.out.println("Мини-СУБД на Java.");
        System.out.println("HELP; — справка, BENCHMARK; — тест индексов, EXIT; — выход");
        System.out.println();

        Scanner sc = new Scanner(System.in);
        StringBuilder buf = new StringBuilder();
        while (true) {
            System.out.print(buf.length() == 0 ? "sql> " : "  -> ");
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;

            buf.append(line).append(' ');
            if (!line.endsWith(";")) continue;

            String q = buf.toString().trim();
            buf.setLength(0);

            if (q.equalsIgnoreCase("EXIT;") || q.equalsIgnoreCase("QUIT;")) {
                System.out.println("Пока.");
                break;
            }
            try {
                long t0 = System.nanoTime();
                String out = engine.execute(q);
                long dt = System.nanoTime() - t0;
                System.out.println(out);
                System.out.printf("(%.2f ms)%n%n", dt / 1e6);
            } catch (Exception e) {
                System.out.println("! " + e.getMessage());
                System.out.println();
            }
        }
    }

    public enum DataType implements Serializable {
        INT, FLOAT, STRING, DATE, BOOLEAN;

        public Comparable parse(String raw) {
            if (raw == null || raw.equalsIgnoreCase("NULL")) return null;
            raw = raw.trim();
            switch (this) {
                case INT:
                    try { return Integer.parseInt(raw); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("нужно INT: " + raw); }
                case FLOAT:
                    try { return Double.parseDouble(raw); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("нужно FLOAT: " + raw); }
                case STRING:
                    if ((raw.startsWith("'") && raw.endsWith("'")) ||
                        (raw.startsWith("\"") && raw.endsWith("\""))) {
                        return raw.substring(1, raw.length() - 1);
                    }
                    return raw;
                case DATE:
                    try { return LocalDate.parse(raw.replace("'", "").replace("\"", "")); }
                    catch (DateTimeParseException e) { throw new IllegalArgumentException("нужна дата YYYY-MM-DD: " + raw); }
                case BOOLEAN:
                    if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false"))
                        return Boolean.parseBoolean(raw);
                    throw new IllegalArgumentException("нужно TRUE/FALSE: " + raw);
                default:
                    throw new IllegalStateException();
            }
        }
    }

    public static class Column implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final DataType type;
        private final boolean pk;
        private final String refTable;
        private final String refColumn;

        public Column(String name, DataType type, boolean pk, String refTable, String refColumn) {
            this.name = name.toLowerCase();
            this.type = type;
            this.pk = pk;
            this.refTable = refTable != null ? refTable.toLowerCase() : null;
            this.refColumn = refColumn != null ? refColumn.toLowerCase() : null;
        }

        public String getName() { return name; }
        public DataType getType() { return type; }
        public boolean isPk() { return pk; }
        public String getRefTable() { return refTable; }
        public String getRefColumn() { return refColumn; }
    }

    public static class Row implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Map<String, Comparable> values = new LinkedHashMap<>();

        public void set(String col, Comparable val) { values.put(col.toLowerCase(), val); }
        public Comparable get(String col) { return values.get(col.toLowerCase()); }
    }

    public static class RowIterator implements Iterator<Row> {
        private final List<Row> rows;
        private int pos = 0;
        public RowIterator(List<Row> rows) { this.rows = rows; }
        public boolean hasNext() { return pos < rows.size(); }
        public Row next() {
            if (!hasNext()) throw new NoSuchElementException();
            return rows.get(pos++);
        }
    }

    /** Индекс на TreeMap для ускорения поиска. */
    public static class TableIndex implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String indexName;
        private final String tableName;
        private final String columnName;
        private final TreeMap<Comparable, Set<Comparable>> tree = new TreeMap<>();

        public TableIndex(String indexName, String tableName, String columnName) {
            this.indexName = indexName.toLowerCase();
            this.tableName = tableName.toLowerCase();
            this.columnName = columnName.toLowerCase();
        }

        public String getIndexName() { return indexName; }
        public String getTableName() { return tableName; }
        public String getColumnName() { return columnName; }

        public void put(Comparable val, Comparable pk) {
            if (val == null) return;
            tree.computeIfAbsent(val, k -> new LinkedHashSet<>()).add(pk);
        }

        public void remove(Comparable val, Comparable pk) {
            if (val == null) return;
            Set<Comparable> pks = tree.get(val);
            if (pks == null) return;
            pks.remove(pk);
            if (pks.isEmpty()) tree.remove(val);
        }

        public Set<Comparable> search(String op, Comparable target) {
            Set<Comparable> res = new LinkedHashSet<>();
            if (target == null) return res;
            switch (op) {
                case "=": {
                    Set<Comparable> s = tree.get(target);
                    if (s != null) res.addAll(s);
                    break;
                }
                case ">":
                    for (Set<Comparable> s : tree.tailMap(target, false).values()) res.addAll(s);
                    break;
                case ">=":
                    for (Set<Comparable> s : tree.tailMap(target, true).values()) res.addAll(s);
                    break;
                case "<":
                    for (Set<Comparable> s : tree.headMap(target, false).values()) res.addAll(s);
                    break;
                case "<=":
                    for (Set<Comparable> s : tree.headMap(target, true).values()) res.addAll(s);
                    break;
                case "!=":
                case "<>":
                    for (Map.Entry<Comparable, Set<Comparable>> e : tree.entrySet())
                        if (e.getKey().compareTo(target) != 0) res.addAll(e.getValue());
                    break;
            }
            return res;
        }
    }

    public static class Table implements Serializable, Iterable<Row> {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final Map<String, Column> columns = new LinkedHashMap<>();
        private String pkColumn;
        private final Map<Comparable, Row> rows = new LinkedHashMap<>();

        public Table(String name, List<Column> cols) {
            this.name = name.toLowerCase();
            for (Column c : cols) {
                columns.put(c.getName(), c);
                if (c.isPk()) pkColumn = c.getName();
            }
            if (pkColumn == null && !cols.isEmpty()) pkColumn = cols.get(0).getName();
        }

        public String getName() { return name; }
        public Map<String, Column> getColumns() { return columns; }
        public String getPkColumn() { return pkColumn; }
        public Map<Comparable, Row> getRows() { return rows; }

        public Iterator<Row> iterator() {
            return new RowIterator(new ArrayList<>(rows.values()));
        }
    }

    /** Сохранение и загрузка таблиц/индексов на диск. */
    public static class Store {
        private final Path dir;

        public Store(String path) {
            this.dir = Paths.get(path);
            try {
                if (!Files.exists(dir)) Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("не удалось создать " + dir + ": " + e.getMessage(), e);
            }
        }

        public void saveTable(Table t)  { save(t, "table_" + t.getName()); }
        public void saveIndex(TableIndex i) { save(i, "index_" + i.getIndexName()); }

        private void save(Object obj, String base) {
            Path target = dir.resolve(base + ".ser");
            Path temp = dir.resolve(base + ".tmp");
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temp)))) {
                oos.writeObject(obj);
            } catch (IOException e) {
                throw new RuntimeException("запись: " + e.getMessage(), e);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException ex) { throw new RuntimeException("move: " + ex.getMessage(), ex); }
            }
        }

        public void deleteTableFiles(String tableName, Collection<TableIndex> idxs) {
            try {
                Files.deleteIfExists(dir.resolve("table_" + tableName.toLowerCase() + ".ser"));
                if (idxs != null)
                    for (TableIndex idx : idxs)
                        Files.deleteIfExists(dir.resolve("index_" + idx.getIndexName() + ".ser"));
            } catch (IOException e) {
                throw new RuntimeException("удаление: " + e.getMessage(), e);
            }
        }

        public Map<String, Table> loadTables() {
            Map<String, Table> out = new HashMap<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "table_*.ser")) {
                for (Path p : ds) {
                    try (ObjectInputStream ois = new ObjectInputStream(
                            new BufferedInputStream(Files.newInputStream(p)))) {
                        Table t = (Table) ois.readObject();
                        out.put(t.getName(), t);
                    } catch (Exception e) {
                        System.err.println("пропущен битый файл " + p.getFileName());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("чтение таблиц: " + e.getMessage(), e);
            }
            return out;
        }

        public List<TableIndex> loadIndexes() {
            List<TableIndex> out = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "index_*.ser")) {
                for (Path p : ds) {
                    try (ObjectInputStream ois = new ObjectInputStream(
                            new BufferedInputStream(Files.newInputStream(p)))) {
                        out.add((TableIndex) ois.readObject());
                    } catch (Exception e) {
                        System.err.println("пропущен битый индекс " + p.getFileName());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("чтение индексов: " + e.getMessage(), e);
            }
            return out;
        }
    }

    public static class Engine {
        private final Store store;
        private final Map<String, Table> tables = new HashMap<>();
        private final Map<String, Map<String, TableIndex>> idxMap = new HashMap<>();

        public Engine(Store store) {
            this.store = store;
            reload();
        }

        private void reload() {
            tables.clear();
            idxMap.clear();
            tables.putAll(store.loadTables());
            for (TableIndex idx : store.loadIndexes()) {
                if (tables.containsKey(idx.getTableName()))
                    idxMap.computeIfAbsent(idx.getTableName(), k -> new HashMap<>())
                          .put(idx.getColumnName(), idx);
            }
        }

        public void initDemoData() {
            if (!tables.isEmpty()) return;
            execute("CREATE TABLE brands (id INT PRIMARY KEY, name STRING, country STRING, founded DATE);");
            execute("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price FLOAT, available BOOLEAN, brand_id INT REFERENCES brands(id));");
            execute("INSERT INTO brands VALUES " +
                    "(1, 'Toyota', 'Japan', '1937-08-28'), " +
                    "(2, 'BMW', 'Germany', '1916-03-07'), " +
                    "(3, 'Ford', 'USA', '1903-06-16');");
            execute("INSERT INTO cars VALUES " +
                    "(1, 'Toyota', 25000.0, TRUE, 1), " +
                    "(2, 'BMW', 45000.0, TRUE, 2), " +
                    "(3, 'Ford', 22000.0, FALSE, 3);");
            execute("CREATE INDEX idx_cars_brand ON cars (brand);");
        }

        public String execute(String raw) {
            String q = raw.trim();
            if (q.endsWith(";")) q = q.substring(0, q.length() - 1).trim();
            if (q.isEmpty()) return "";

            String up = q.toUpperCase();
            if (up.equals("HELP")) return help();
            if (up.equals("SHOW TABLES")) return showTables();
            if (up.equals("BENCHMARK")) return benchmark();
            if (up.startsWith("CREATE TABLE")) return createTable(q);
            if (up.startsWith("DROP TABLE")) return dropTable(q);
            if (up.startsWith("CREATE INDEX")) return createIndex(q);
            if (up.startsWith("INSERT INTO")) return insert(q);
            if (up.startsWith("SELECT")) return select(q);
            if (up.startsWith("UPDATE")) return update(q);
            if (up.startsWith("DELETE FROM")) return delete(q);
            throw new IllegalArgumentException("неизвестная команда: " + q);
        }

        private String createTable(String q) {
            Matcher m = Pattern.compile("(?i)^CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.+)\\)$").matcher(q);
            if (!m.matches()) throw new IllegalArgumentException("CREATE TABLE: неверный синтаксис");
            String tName = m.group(1).toLowerCase();
            if (tables.containsKey(tName)) throw new IllegalArgumentException("таблица " + tName + " уже есть");

            List<Column> cols = new ArrayList<>();
            Pattern cp = Pattern.compile("(?i)^(\\w+)\\s+(\\w+)(?:\\s+(PRIMARY\\s+KEY))?(?:\\s+REFERENCES\\s+(\\w+)\\s*\\((\\w+)\\))?$");
            for (String def : m.group(2).split(",")) {
                Matcher cm = cp.matcher(def.trim());
                if (!cm.matches()) throw new IllegalArgumentException("плохое определение столбца: " + def.trim());
                String cn = cm.group(1).toLowerCase();
                DataType t = DataType.valueOf(cm.group(2).toUpperCase());
                boolean pk = cm.group(3) != null;
                String rt = cm.group(4);
                String rc = cm.group(5);
                if (rt != null) {
                    Table target = tables.get(rt.toLowerCase());
                    if (target == null) throw new IllegalArgumentException("нет таблицы " + rt);
                    if (!target.getColumns().containsKey(rc.toLowerCase()))
                        throw new IllegalArgumentException("нет столбца " + rc + " в " + rt);
                }
                cols.add(new Column(cn, t, pk, rt, rc));
            }
            Table t = new Table(tName, cols);
            tables.put(tName, t);
            store.saveTable(t);
            return "OK: таблица " + tName;
        }

        private String dropTable(String q) {
            Matcher m = Pattern.compile("(?i)^DROP\\s+TABLE\\s+(\\w+)$").matcher(q);
            if (!m.matches()) throw new IllegalArgumentException("DROP TABLE: неверный синтаксис");
            String tName = m.group(1).toLowerCase();
            if (!tables.containsKey(tName)) throw new IllegalArgumentException("нет таблицы " + tName);
            
            for (Table other : tables.values()) {
                if (other.getName().equals(tName)) continue;
                for (Column c : other.getColumns().values())
                    if (tName.equals(c.getRefTable()))
                        throw new IllegalStateException("на " + tName + " ссылается " + other.getName());
            }
            tables.remove(tName);
            Map<String, TableIndex> idxs = idxMap.remove(tName);
            store.deleteTableFiles(tName, idxs != null ? idxs.values() : null);
            return "OK: " + tName + " удалена";
        }

        private String createIndex(String q) {
            Matcher m = Pattern.compile("(?i)^CREATE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)$").matcher(q);
            if (!m.matches()) throw new IllegalArgumentException("CREATE INDEX: пример: CREATE INDEX i ON t (c)");
            String iName = m.group(1).toLowerCase();
            String tName = m.group(2).toLowerCase();
            String cName = m.group(3).toLowerCase();

            Table t = getTable(tName);
            if (!t.getColumns().containsKey(cName))
                throw new IllegalArgumentException("нет столбца " + cName);

            TableIndex idx = new TableIndex(iName, tName, cName);
            for (Row r : t) idx.put(r.get(cName), r.get(t.getPkColumn()));
            idxMap.computeIfAbsent(tName, k -> new HashMap<>()).put(cName, idx);
            store.saveIndex(idx);
            return "OK: индекс " + iName;
        }

        private String insert(String q) {
            Matcher m = Pattern.compile("(?i)^INSERT\\s+INTO\\s+(\\w+)\\s*(?:\\(([^)]+)\\))?\\s*VALUES\\s*(.+)$").matcher(q);
            if (!m.matches()) throw new IllegalArgumentException("INSERT: неверный синтаксис");

            String tName = m.group(1).toLowerCase();
            String colsGroup = m.group(2);
            String vals = m.group(3).trim();

            Table t = getTable(tName);
            List<String> target;
            if (colsGroup != null && !colsGroup.trim().isEmpty()) {
                target = new ArrayList<>();
                for (String c : colsGroup.split(",")) {
                    String cn = c.trim().toLowerCase();
                    if (!t.getColumns().containsKey(cn)) throw new IllegalArgumentException("нет столбца " + cn);
                    target.add(cn);
                }
            } else {
                target = new ArrayList<>(t.getColumns().keySet());
            }

            List<Row> staged = new ArrayList<>();
            Set<Comparable> batchPks = new HashSet<>();
            String pkCol = t.getPkColumn();

            Matcher tm = Pattern.compile("\\(([^)]+)\\)").matcher(vals);
            while (tm.find()) {
                List<String> parts = splitCsv(tm.group(1));
                if (parts.size() != target.size())
                    throw new IllegalArgumentException("колонок " + target.size() + ", значений " + parts.size());

                Row r = new Row();
                for (String c : t.getColumns().keySet()) r.set(c, null);
                for (int i = 0; i < target.size(); i++) {
                    String cn = target.get(i);
                    Column meta = t.getColumns().get(cn);
                    Comparable v = meta.getType().parse(parts.get(i));

                    if (meta.getRefTable() != null && v != null) {
                        Table ref = getTable(meta.getRefTable());
                        String refCol = meta.getRefColumn() != null ? meta.getRefColumn() : ref.getPkColumn();
                        boolean ok;
                        if (refCol.equals(ref.getPkColumn())) {
                            ok = ref.getRows().containsKey(v);
                        } else {
                            ok = false;
                            for (Row rr : ref) if (v.equals(rr.get(refCol))) { ok = true; break; }
                        }
                        if (!ok)
                            throw new IllegalArgumentException("FK: " + v + " нет в " + ref.getName() + "." + refCol);
                    }
                    r.set(cn, v);
                }

                Comparable pk = r.get(pkCol);
                if (pk == null) throw new IllegalArgumentException("PK " + pkCol + " = NULL");
                if (t.getRows().containsKey(pk) || !batchPks.add(pk))
                    throw new IllegalArgumentException("дубликат PK: " + pkCol + "=" + pk);
                staged.add(r);
            }
            if (staged.isEmpty()) throw new IllegalArgumentException("нет значений в VALUES");

            Map<String, TableIndex> idxs = idxMap.get(tName);
            for (Row r : staged) {
                Comparable pk = r.get(pkCol);
                t.getRows().put(pk, r);
                if (idxs != null)
                    for (TableIndex idx : idxs.values())
                        idx.put(r.get(idx.getColumnName()), pk);
            }
            persist(t);
            return "OK: добавлено " + staged.size();
        }

        private String select(String q) {
            Matcher jm = Pattern.compile(
                    "(?i)^SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)\\s+JOIN\\s+(\\w+)\\s+ON\\s+(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)(?:\\s+WHERE\\s+(.+))?$"
            ).matcher(q);
            if (jm.matches()) return selectJoin(jm);

            Matcher m = Pattern.compile("(?i)^SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$").matcher(q);
            if (!m.matches()) throw new IllegalArgumentException("SELECT: неверный синтаксис");

            String colsPart = m.group(1).trim();
            String tName = m.group(2).toLowerCase();
            String where = m.group(3);

            Table t = getTable(tName);
            List<String> headers = resolveCols(colsPart, t);

            List<Row> result;
            String plan;

            if (where == null || where.trim().isEmpty()) {
                result = new ArrayList<>(t.getRows().values());
                plan = "full scan";
            } else {
                Cond cond = parseCond(where, t);
                Map<String, TableIndex> idxs = idxMap.get(tName);

                if (cond.col.equals(t.getPkColumn()) && cond.op.equals("=")) {
                    Row r = t.getRows().get(cond.val);
                    result = new ArrayList<>();
                    if (r != null) result.add(r);
                    plan = "PK lookup";
                } else if (idxs != null && idxs.containsKey(cond.col)) {
                    TableIndex idx = idxs.get(cond.col);
                    Set<Comparable> pks = idx.search(cond.op, cond.val);
                    result = new ArrayList<>(pks.size());
                    for (Comparable pk : pks) {
                        Row r = t.getRows().get(pk);
                        if (r != null) result.add(r);
                    }
                    plan = "index " + idx.getIndexName();
                } else {
                    result = new ArrayList<>();
                    for (Row r : t) if (cond.match(r)) result.add(r);
                    plan = "full scan";
                }
            }

            return formatTable(headers, result) + "\n[" + plan + ", строк: " + result.size() + "]";
        }

        private String selectJoin(Matcher jm) {
            String colsPart = jm.group(1).trim();
            String lName = jm.group(2).toLowerCase();
            String rName = jm.group(3).toLowerCase();
            String t1 = jm.group(4).toLowerCase();
            String c1 = jm.group(5).toLowerCase();
            String t2 = jm.group(6).toLowerCase();
            String c2 = jm.group(7).toLowerCase();
            String where = jm.group(8);

            Table lt = getTable(lName);
            Table rt = getTable(rName);

            String lCol = t1.equals(lName) ? c1 : c2;
            String rCol = t2.equals(rName) ? c2 : c1;

            List<String> headers = new ArrayList<>();
            if (colsPart.equals("*")) {
                for (String c : lt.getColumns().keySet()) headers.add(lName + "." + c);
                for (String c : rt.getColumns().keySet()) headers.add(rName + "." + c);
            } else {
                for (String p : colsPart.split(",")) headers.add(p.trim().toLowerCase());
            }

            List<Row> out = new ArrayList<>();
            for (Row lr : lt) {
                Comparable lv = lr.get(lCol);
                if (lv == null) continue;
                for (Row rr : rt) {
                    if (lv.equals(rr.get(rCol))) {
                        Row comb = new Row();
                        for (String c : lt.getColumns().keySet()) {
                            comb.set(lName + "." + c, lr.get(c));
                            comb.set(c, lr.get(c));
                        }
                        for (String c : rt.getColumns().keySet()) {
                            comb.set(rName + "." + c, rr.get(c));
                            if (comb.get(c) == null) comb.set(c, rr.get(c));
                        }
                        out.add(comb);
                    }
                }
            }

            if (where != null && !where.trim().isEmpty()) {
                String w = where.trim();
                Table ctx = lt;
                if (w.contains(".")) {
                    String pfx = w.substring(0, w.indexOf('.')).trim().toLowerCase();
                    if (pfx.equals(rName)) ctx = rt;
                    w = w.substring(w.indexOf('.') + 1);
                }
                Cond cond = parseCond(w, ctx);
                out.removeIf(r -> !cond.match(r));
            }

            return formatTable(headers, out) + "\n[join, строк: " + out.size() + "]";
        }

        private String update(String q) {
            Matcher m = Pattern.compile("(?i)^UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$").matcher(q);
            if (!m.matches()) throw new IllegalArgumentException("UPDATE: неверный синтаксис");
            String tName = m.group(1).toLowerCase();
            String setPart = m.group(2).trim();
            String where = m.group(3);

            Table t = getTable(tName);
            Map<String, Comparable> upd = new LinkedHashMap<>();
            for (String a : splitCsv(setPart)) {
                String[] kv = a.split("=", 2);
                if (kv.length != 2) throw new IllegalArgumentException("SET: " + a);
                String cn = kv[0].trim().toLowerCase();
                Column meta = t.getColumns().get(cn);
                if (meta == null) throw new IllegalArgumentException("нет столбца " + cn);
                if (meta.isPk()) throw new IllegalArgumentException("PK " + cn + " менять нельзя");
                Comparable v = meta.getType().parse(kv[1].trim());
                if (meta.getRefTable() != null && v != null) {
                    Table ref = getTable(meta.getRefTable());
                    String rc = meta.getRefColumn() != null ? meta.getRefColumn() : ref.getPkColumn();
                    boolean ok;
                    if (rc.equals(ref.getPkColumn())) {
                        ok = ref.getRows().containsKey(v);
                    } else {
                        ok = false;
                        for (Row rr : ref) if (v.equals(rr.get(rc))) { ok = true; break; }
                    }
                    if (!ok) throw new IllegalArgumentException("FK: " + v + " нет в " + ref.getName());
                }
                upd.put(cn, v);
            }

            Cond cond = (where != null && !where.trim().isEmpty()) ? parseCond(where, t) : null;
            String pkCol = t.getPkColumn();
            Map<String, TableIndex> idxs = idxMap.get(tName);
            int n = 0;

            for (Row r : t) {
                if (cond != null && !cond.match(r)) continue;
                Comparable pk = r.get(pkCol);
                for (Map.Entry<String, Comparable> e : upd.entrySet()) {
                    String c = e.getKey();
                    Comparable oldV = r.get(c);
                    if (idxs != null && idxs.containsKey(c)) {
                        TableIndex idx = idxs.get(c);
                        idx.remove(oldV, pk);
                        idx.put(e.getValue(), pk);
                    }
                    r.set(c, e.getValue());
                }
                n++;
            }
            if (n > 0) persist(t);
            return "OK: обновлено " + n;
        }

        private String delete(String q) {
            Matcher m = Pattern.compile("(?i)^DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$").matcher(q);
            if (!m.matches()) throw new IllegalArgumentException("DELETE: неверный синтаксис");
            String tName = m.group(1).toLowerCase();
            String where = m.group(2);
            Table t = getTable(tName);
            Cond cond = (where != null && !where.trim().isEmpty()) ? parseCond(where, t) : null;

            String pkCol = t.getPkColumn();
            List<Comparable> pks = new ArrayList<>();
            for (Row r : t) {
                if (cond == null || cond.match(r)) {
                    Comparable pk = r.get(pkCol);
                    checkRefs(tName, pk);
                    pks.add(pk);
                }
            }

            Map<String, TableIndex> idxs = idxMap.get(tName);
            for (Comparable pk : pks) {
                Row r = t.getRows().remove(pk);
                if (r != null && idxs != null)
                    for (TableIndex idx : idxs.values()) idx.remove(r.get(idx.getColumnName()), pk);
            }
            if (!pks.isEmpty()) persist(t);
            return "OK: удалено " + pks.size();
        }

        // FIXME: FK только на PK родителя.
        private void checkRefs(String parentName, Comparable pk) {
            for (Table other : tables.values()) {
                for (Column c : other.getColumns().values()) {
                    if (!parentName.equals(c.getRefTable())) continue;
                    for (Row r : other)
                        if (pk.equals(r.get(c.getName())))
                            throw new IllegalStateException(
                                    "FK: " + pk + " используется в " + other.getName() + "." + c.getName());
                }
            }
        }

        private void persist(Table t) {
            store.saveTable(t);
            Map<String, TableIndex> idxs = idxMap.get(t.getName());
            if (idxs != null)
                for (TableIndex idx : idxs.values()) store.saveIndex(idx);
        }

        private Table getTable(String name) {
            Table t = tables.get(name.toLowerCase());
            if (t == null) throw new IllegalArgumentException("нет таблицы " + name);
            return t;
        }

        private List<String> resolveCols(String s, Table t) {
            List<String> out = new ArrayList<>();
            if (s.equals("*")) {
                out.addAll(t.getColumns().keySet());
            } else {
                for (String c : s.split(",")) {
                    String cn = c.trim().toLowerCase();
                    if (!t.getColumns().containsKey(cn))
                        throw new IllegalArgumentException("нет столбца " + cn);
                    out.add(cn);
                }
            }
            return out;
        }

        private static class Cond {
            final String col;
            final String op;
            final Comparable val;
            Cond(String col, String op, Comparable val) {
                this.col = col; this.op = op; this.val = val;
            }
            boolean match(Row r) {
                Comparable v = r.get(col);
                if (v == null || val == null) return false;
                int c = v.compareTo(val);
                switch (op) {
                    case "=": return c == 0;
                    case "!=":
                    case "<>": return c != 0;
                    case ">": return c > 0;
                    case "<": return c < 0;
                    case ">=": return c >= 0;
                    case "<=": return c <= 0;
                }
                return false;
            }
        }

        // TODO: AND/OR в WHERE.
        private Cond parseCond(String s, Table t) {
            Matcher m = Pattern.compile("^(\\w+)\\s*(>=|<=|!=|<>|=|>|<)\\s*(.+)$").matcher(s.trim());
            if (!m.matches()) throw new IllegalArgumentException("WHERE: " + s);
            String cn = m.group(1).toLowerCase();
            Column meta = t.getColumns().get(cn);
            if (meta == null) throw new IllegalArgumentException("WHERE: нет столбца " + cn);
            return new Cond(cn, m.group(2), meta.getType().parse(m.group(3).trim()));
        }

        private List<String> splitCsv(String s) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean sq = false, dq = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\'' && !dq) { sq = !sq; cur.append(c); }
                else if (c == '"' && !sq) { dq = !dq; cur.append(c); }
                else if (c == ',' && !sq && !dq) { out.add(cur.toString().trim()); cur.setLength(0); }
                else cur.append(c);
            }
            if (cur.length() > 0) out.add(cur.toString().trim());
            return out;
        }

        private String formatTable(List<String> headers, List<Row> rows) {
            int[] w = new int[headers.size()];
            for (int i = 0; i < headers.size(); i++) w[i] = headers.get(i).length();
            for (Row r : rows)
                for (int i = 0; i < headers.size(); i++) {
                    Object v = r.get(headers.get(i));
                    w[i] = Math.max(w[i], (v == null ? "NULL" : v.toString()).length());
                }

            String sep = sep(w);
            StringBuilder sb = new StringBuilder();
            sb.append(sep).append('\n').append(line(headers, w)).append('\n').append(sep).append('\n');
            for (Row r : rows) {
                List<String> cells = new ArrayList<>(headers.size());
                for (String h : headers) {
                    Object v = r.get(h);
                    cells.add(v == null ? "NULL" : v.toString());
                }
                sb.append(line(cells, w)).append('\n');
            }
            sb.append(sep);
            return sb.toString();
        }

        // Java 8: repeat() еще нет.
        private String sep(int[] w) {
            StringBuilder sb = new StringBuilder("+");
            for (int x : w) {
                for (int i = 0; i <= x + 1; i++) sb.append('-');
                sb.append('+');
            }
            return sb.toString();
        }

        private String line(List<String> cells, int[] w) {
            StringBuilder sb = new StringBuilder("|");
            for (int i = 0; i < cells.size(); i++)
                sb.append(' ').append(String.format("%-" + w[i] + "s", cells.get(i))).append(" |");
            return sb.toString();
        }

        private String showTables() {
            if (tables.isEmpty()) return "нет таблиц";
            StringBuilder sb = new StringBuilder();
            for (Table t : tables.values()) {
                sb.append(t.getName()).append(" (").append(t.getRows().size()).append(" строк): ");
                List<String> parts = new ArrayList<>();
                for (Column c : t.getColumns().values()) {
                    String p = c.getName() + ":" + c.getType();
                    if (c.isPk()) p += " [PK]";
                    if (c.getRefTable() != null) p += " [FK->" + c.getRefTable() + "]";
                    parts.add(p);
                }
                sb.append(String.join(", ", parts)).append('\n');
                Map<String, TableIndex> idxs = idxMap.get(t.getName());
                if (idxs != null && !idxs.isEmpty()) {
                    List<String> ix = new ArrayList<>();
                    for (TableIndex i : idxs.values()) ix.add(i.getIndexName() + "(" + i.getColumnName() + ")");
                    sb.append("  индексы: ").append(String.join(", ", ix)).append('\n');
                }
            }
            return sb.toString();
        }

        private String benchmark() {
            StringBuilder rep = new StringBuilder();
            rep.append("Бенчмарк: 50 000 строк\n");

            if (tables.containsKey("bench_cars")) execute("DROP TABLE bench_cars;");
            execute("CREATE TABLE bench_cars (id INT PRIMARY KEY, brand STRING, price FLOAT, created DATE, active BOOLEAN);");
            Table bt = tables.get("bench_cars");
            String[] brands = {"Audi", "BMW", "Chevrolet", "Ford", "Honda", "Hyundai", "Kia",
                               "Mazda", "Mercedes", "Nissan", "Toyota", "Volkswagen", "Volvo"};
            Random rnd = new Random(42);

            long t0 = System.nanoTime();
            for (int i = 1; i <= 50_000; i++) {
                Row r = new Row();
                r.set("id", i);
                r.set("brand", (i == 42_500) ? "Bugatti" : brands[rnd.nextInt(brands.length)]);
                r.set("price", 10000.0 + rnd.nextInt(90000));
                r.set("created", LocalDate.of(2020, 1, 1).plusDays(i % 1500));
                r.set("active", i % 2 == 0);
                bt.getRows().put(i, r);
            }
            store.saveTable(bt);
            rep.append(String.format("  генерация+сохранение: %.1f ms%n", (System.nanoTime() - t0) / 1e6));

            long a = System.nanoTime();
            for (int i = 0; i < 100; i++) select("SELECT * FROM bench_cars WHERE brand = 'Bugatti'");
            double noIdx = (System.nanoTime() - a) / 1e6 / 100;

            long b = System.nanoTime();
            execute("CREATE INDEX idx_bench_brand ON bench_cars (brand);");
            double build = (System.nanoTime() - b) / 1e6;

            long c = System.nanoTime();
            for (int i = 0; i < 100; i++) select("SELECT * FROM bench_cars WHERE brand = 'Bugatti'");
            double withIdx = (System.nanoTime() - c) / 1e6 / 100;

            execute("DROP TABLE bench_cars;");

            rep.append(String.format("  построение индекса: %.1f ms%n", build));
            rep.append(String.format("  SELECT без индекса: %.3f ms%n", noIdx));
            rep.append(String.format("  SELECT с индексом:  %.3f ms%n", withIdx));
            rep.append(String.format("  ускорение: x%.1f%n", noIdx / Math.max(withIdx, 0.0001)));
            return rep.toString();
        }

        private String help() {
            return "Команды (в конце ';'):\n" +
                   "  SHOW TABLES;\n" +
                   "  CREATE TABLE t (id INT PRIMARY KEY, name STRING, ...);\n" +
                   "  DROP TABLE t;\n" +
                   "  CREATE INDEX idx ON t (col);\n" +
                   "  INSERT INTO t VALUES (...), (...);\n" +
                   "  SELECT * FROM t [WHERE col op val];\n" +
                   "  SELECT ... FROM t1 JOIN t2 ON t1.a = t2.b;\n" +
                   "  UPDATE t SET col = val WHERE ...;\n" +
                   "  DELETE FROM t WHERE ...;\n" +
                   "  BENCHMARK;  — тест индексов на 50k строк\n" +
                   "  EXIT;";
        }
    }
}