package functions.custom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import expr.CellRef;

public class Dependents {

    private final List<CellRef> list = new ArrayList<>();

    public List<CellRef> getList() {
        return list;
    }

    public void addDependency(CellRef ref, Map<String, Cell> cellMap) {
        if (ref == null || list.contains(ref)) {
            return;
        }
        list.add(ref);
        orderDependencies(cellMap);
    }

    public void delDependency(CellRef ref, Map<String, Cell> cellMap) {
        list.remove(ref);
        orderDependencies(cellMap);
    }

    public void reorder(Map<String, Cell> cellMap) {
        orderDependencies(cellMap);
    }

    private void orderDependencies(Map<String, Cell> cellMap) {
        int n = list.size();
        if (n < 2) {
            return;
        }
        List<Set<Integer>> out = new ArrayList<>(n);
        int[] indeg = new int[n];
        for (int i = 0; i < n; i++) {
            out.add(new HashSet<>());
        }
        for (int j = 0; j < n; j++) {
            Cell cj = cellMap.get(list.get(j).toString());
            if (cj == null || cj.getReferenced() == null) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                if (i != j && cj.getReferenced().contains(list.get(i))) {
                    if (out.get(i).add(j)) {
                        indeg[j]++;
                    }
                }
            }
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (indeg[i] == 0) {
                queue.add(i);
            }
        }
        List<CellRef> sorted = new ArrayList<>(n);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            sorted.add(list.get(i));
            for (int j : out.get(i)) {
                indeg[j]--;
                if (indeg[j] == 0) {
                    queue.add(j);
                }
            }
        }
        if (sorted.size() == n) {
            list.clear();
            list.addAll(sorted);
        }
    }
}