package im.manus.universalhost

/**
 * Модель экрана модулей: корневой список, где каждый элемент — модуль или папка с модулями.
 * Чистый Kotlin без Android, поэтому её можно проверять обычным JVM-тестом.
 */
class ModuleLayout {

    class Node(
        val id: String,
        val isFolder: Boolean,
        var name: String = "",
        val items: MutableList<String> = mutableListOf()
    )

    class Cell(val id: String, val isFolder: Boolean, val members: List<String>)

    val root = mutableListOf<Node>()

    fun folder(id: String): Node? = root.firstOrNull { it.isFolder && it.id == id }

    fun cells(folderId: String?): List<Cell> {
        if (folderId == null) {
            return root.map { Cell(it.id, it.isFolder, if (it.isFolder) it.items.toList() else listOf(it.id)) }
        }
        val f = folder(folderId) ?: return emptyList()
        return f.items.map { Cell(it, false, listOf(it)) }
    }

    /** Имена модулей в порядке отображения (папки раскрываются на месте). */
    fun flat(): List<String> {
        val out = mutableListOf<String>()
        for (n in root) if (n.isFolder) out.addAll(n.items) else out.add(n.id)
        return out
    }

    /** Приводит раскладку в соответствие с реально найденными модулями. */
    fun sync(available: List<String>) {
        val avail = LinkedHashSet(available)
        val seen = HashSet<String>()
        val iter = root.listIterator()
        while (iter.hasNext()) {
            val n = iter.next()
            if (n.isFolder) {
                n.items.retainAll { name -> name in avail && seen.add(name) }
                when (n.items.size) {
                    0 -> iter.remove()
                    1 -> iter.set(Node(n.items[0], false))   // папка из одного модуля распадается
                }
            } else if (n.id !in avail || !seen.add(n.id)) {
                iter.remove()
            }
        }
        for (a in avail) if (seen.add(a)) root.add(Node(a, false))
    }

    /** Перестановка внутри корня или папки: элемент занимает место целевой ячейки. */
    fun reorder(folderId: String?, from: Int, to: Int): Boolean {
        if (folderId == null) return moveIn(root, from, to)
        val f = folder(folderId) ?: return false
        return moveIn(f.items, from, to)
    }

    private fun <T> moveIn(list: MutableList<T>, from: Int, to: Int): Boolean {
        if (from !in list.indices || to !in list.indices || from == to) return false
        val item = list.removeAt(from)
        list.add(to, item)
        return true
    }

    /**
     * Перетаскивание модуля [from] на ячейку [target] в корне: на модуль — создаётся папка,
     * на папку — модуль кладётся внутрь. Папку в папку положить нельзя.
     */
    fun merge(from: Int, target: Int): Boolean {
        if (from !in root.indices || target !in root.indices || from == target) return false
        val dragged = root[from]
        if (dragged.isFolder) return false
        val tgt = root[target]
        if (tgt.isFolder) {
            tgt.items.add(dragged.id)
            root.removeAt(from)
            return true
        }
        val folderNode = Node(nextFolderId(), true, "", mutableListOf(tgt.id, dragged.id))
        root.removeAt(from)
        val tIdx = if (from < target) target - 1 else target
        root[tIdx] = folderNode
        return true
    }

    /** Вынести модуль из папки в корень (сразу после папки). Папка из одного модуля распадается. */
    fun moveOut(folderId: String, index: Int): Boolean {
        val f = folder(folderId) ?: return false
        if (index !in f.items.indices) return false
        val name = f.items.removeAt(index)
        val fIdx = root.indexOf(f)
        when (f.items.size) {
            0 -> root[fIdx] = Node(name, false)
            1 -> {
                root[fIdx] = Node(f.items[0], false)
                root.add(fIdx + 1, Node(name, false))
            }
            else -> root.add(fIdx + 1, Node(name, false))
        }
        return true
    }

    fun dissolve(folderId: String): Boolean {
        val f = folder(folderId) ?: return false
        val idx = root.indexOf(f)
        root.removeAt(idx)
        root.addAll(idx, f.items.map { Node(it, false) })
        return true
    }

    fun rename(folderId: String, name: String) {
        folder(folderId)?.name = sanitize(name)
    }

    private fun nextFolderId(): String {
        var max = 0
        for (n in root) if (n.isFolder) n.id.removePrefix(PREFIX).toIntOrNull()?.let { if (it > max) max = it }
        return PREFIX + (max + 1)
    }

    fun serialize(): String {
        val sb = StringBuilder()
        for (n in root) {
            if (n.isFolder) {
                sb.append("f\t").append(n.id).append('\t').append(sanitize(n.name)).append('\t')
                    .append(n.items.joinToString(SEP)).append('\n')
            } else {
                sb.append("m\t").append(n.id).append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        const val PREFIX = "#folder"
        private const val SEP = "\u0001"

        fun isFolderId(id: String) = id.startsWith(PREFIX)

        fun sanitize(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace(SEP, " ").trim()

        fun parse(text: String): ModuleLayout {
            val l = ModuleLayout()
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val p = line.split('\t')
                when {
                    p[0] == "m" && p.size >= 2 && p[1].isNotEmpty() -> l.root.add(Node(p[1], false))
                    p[0] == "f" && p.size >= 4 && p[1].startsWith(PREFIX) ->
                        l.root.add(Node(p[1], true, p[2], p[3].split(SEP).filter { it.isNotEmpty() }.toMutableList()))
                }
            }
            return l
        }
    }
}
