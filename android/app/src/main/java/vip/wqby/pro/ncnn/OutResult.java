package vip.wqby.pro.ncnn;

/**
 * JNI 返回结构。字段/构造顺序必须与 libmyapplication.so 一致。
 * 构造：(x1, y1, x2, y2, classId, confidence)
 */
public class OutResult {
    public int classId;
    public float confidence;
    public int x1;
    public int y1;
    public int x2;
    public int y2;

    public OutResult() {
    }

    public OutResult(int x1, int y1, int x2, int y2, int classId, float confidence) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.classId = classId;
        this.confidence = confidence;
    }

    public int getClassId() {
        return classId;
    }

    public float getConfidence() {
        return confidence;
    }

    public int getX1() {
        return x1;
    }

    public int getX2() {
        return x2;
    }

    public int getY1() {
        return y1;
    }

    public int getY2() {
        return y2;
    }

    public void setClassId(int classId) {
        this.classId = classId;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    public void setX1(int x1) {
        this.x1 = x1;
    }

    public void setX2(int x2) {
        this.x2 = x2;
    }

    public void setY1(int y1) {
        this.y1 = y1;
    }

    public void setY2(int y2) {
        this.y2 = y2;
    }

    public int centerX() {
        return (x1 + x2) / 2;
    }

    public int centerY() {
        return (y1 + y2) / 2;
    }
}
