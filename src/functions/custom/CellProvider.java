package functions.custom;

public interface CellProvider {
    Cell at(int row, int col);

    int rows();

    int cols();
}