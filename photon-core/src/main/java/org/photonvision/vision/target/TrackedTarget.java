/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.photonvision.vision.target;

import edu.wpi.first.math.geometry.Transform3d;
import java.util.HashMap;
import java.util.List;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.photonvision.common.util.math.MathUtils;
import org.photonvision.vision.apriltag.DetectionResult;
import org.photonvision.vision.frame.FrameStaticProperties;
import org.photonvision.vision.opencv.*;

public class TrackedTarget implements Releasable {
    public final Contour m_mainContour;
    public List<Contour> m_subContours; // can be empty

    private MatOfPoint2f m_approximateBoundingPolygon;

    private List<Point> m_targetCorners;

    private Point m_targetOffsetPoint;
    private Point m_robotOffsetPoint;

    private double m_pitch;
    private double m_yaw;
    private double m_area;
    private double m_skew;

    private Transform3d m_cameraToTarget3d = new Transform3d();

    private CVShape m_shape;

    private int m_fiducialId = -1;
    private double m_poseAmbiguity = -1;

    private Mat m_cameraRelativeTvec, m_cameraRelativeRvec;

    public TrackedTarget(
            PotentialTarget origTarget, TargetCalculationParameters params, CVShape shape) {
        this.m_mainContour = origTarget.m_mainContour;
        this.m_subContours = origTarget.m_subContours;
        this.m_shape = shape;
        calculateValues(params);
    }

    public TrackedTarget(DetectionResult result, TargetCalculationParameters params) {
        m_targetOffsetPoint = new Point(result.getCenterX(), result.getCenterY());
        m_robotOffsetPoint = new Point();

        m_pitch =
                TargetCalculations.calculatePitch(
                        result.getCenterY(), params.cameraCenterPoint.y, params.verticalFocalLength);
        m_yaw =
                TargetCalculations.calculateYaw(
                        result.getCenterX(), params.cameraCenterPoint.x, params.horizontalFocalLength);
        var bestPose = new Transform3d();
        if (result.getError1() <= result.getError2()) {
            bestPose = result.getPoseResult1();
        } else {
            bestPose = result.getPoseResult2();
        }

        bestPose = MathUtils.convertApriltagtoOpenCV(bestPose);

        m_cameraToTarget3d = bestPose;

        double[] corners = result.getCorners();
        Point[] cornerPoints =
                new Point[] {
                    new Point(corners[0], corners[1]),
                    new Point(corners[2], corners[3]),
                    new Point(corners[4], corners[5]),
                    new Point(corners[6], corners[7])
                };
        m_targetCorners = List.of(cornerPoints);
        MatOfPoint contourMat = new MatOfPoint(cornerPoints);
        m_approximateBoundingPolygon = new MatOfPoint2f(cornerPoints);
        m_mainContour = new Contour(contourMat);
        m_area = m_mainContour.getArea() / params.imageArea * 100;
        m_fiducialId = result.getId();
        m_shape = null;

        // TODO implement skew? or just yeet
        m_skew = 0;

        var tvec = new Mat(3, 1, CvType.CV_64FC1);
        tvec.put(
                0,
                0,
                new double[] {
                    bestPose.getTranslation().getX(),
                    bestPose.getTranslation().getY(),
                    bestPose.getTranslation().getZ()
                });
        setCameraRelativeTvec(tvec);

        // Opencv expects a 3d vector with norm = angle and direction = axis
        var rvec = new Mat(3, 1, CvType.CV_64FC1);
        MathUtils.rotationToOpencvRvec(bestPose.getRotation(), rvec);
        setCameraRelativeRvec(rvec);

        m_poseAmbiguity = result.getPoseAmbiguity();
    }

    public void setFiducialId(int id) {
        m_fiducialId = id;
    }

    public int getFiducialId() {
        return m_fiducialId;
    }

    public void setPoseAmbiguity(double ambiguity) {
        m_poseAmbiguity = ambiguity;
    }

    public double getPoseAmbiguity() {
        return m_poseAmbiguity;
    }

    /**
     * Set the approximate bouding polygon.
     *
     * @param boundingPolygon List of points to copy. Not modified.
     */
    public void setApproximateBoundingPolygon(MatOfPoint2f boundingPolygon) {
        if (m_approximateBoundingPolygon == null) m_approximateBoundingPolygon = new MatOfPoint2f();
        boundingPolygon.copyTo(m_approximateBoundingPolygon);
    }

    public Point getTargetOffsetPoint() {
        return m_targetOffsetPoint;
    }

    public Point getRobotOffsetPoint() {
        return m_robotOffsetPoint;
    }

    public double getPitch() {
        return m_pitch;
    }

    public double getYaw() {
        return m_yaw;
    }

    public double getSkew() {
        return m_skew;
    }

    public double getArea() {
        return m_area;
    }

    public RotatedRect getMinAreaRect() {
        return m_mainContour.getMinAreaRect();
    }

    public MatOfPoint2f getApproximateBoundingPolygon() {
        return m_approximateBoundingPolygon;
    }

    public void calculateValues(TargetCalculationParameters params) {
        // this MUST happen in this exact order!
        m_targetOffsetPoint =
                TargetCalculations.calculateTargetOffsetPoint(
                        params.isLandscape, params.targetOffsetPointEdge, getMinAreaRect());
        m_robotOffsetPoint =
                TargetCalculations.calculateRobotOffsetPoint(
                        params.robotOffsetSinglePoint,
                        params.cameraCenterPoint,
                        params.dualOffsetValues,
                        params.robotOffsetPointMode);

        // order of this stuff doesnt matter though
        m_pitch =
                TargetCalculations.calculatePitch(
                        m_targetOffsetPoint.y, m_robotOffsetPoint.y, params.verticalFocalLength);
        m_yaw =
                TargetCalculations.calculateYaw(
                        m_targetOffsetPoint.x, m_robotOffsetPoint.x, params.horizontalFocalLength);
        m_area = m_mainContour.getMinAreaRect().size.area() / params.imageArea * 100;

        m_skew = TargetCalculations.calculateSkew(params.isLandscape, getMinAreaRect());
    }

    @Override
    public void release() {
        m_mainContour.release();

        // TODO how can this check fail?
        if (m_subContours != null) {
            for (var sc : m_subContours) {
                sc.release();
            }
        }

        if (m_cameraRelativeTvec != null) m_cameraRelativeTvec.release();
        if (m_cameraRelativeRvec != null) m_cameraRelativeRvec.release();
    }

    public void setCorners(List<Point> targetCorners) {
        this.m_targetCorners = targetCorners;
    }

    public List<Point> getTargetCorners() {
        return m_targetCorners;
    }

    public boolean hasSubContours() {
        return !m_subContours.isEmpty();
    }

    public Transform3d getCameraToTarget3d() {
        return m_cameraToTarget3d;
    }

    public void setCameraToTarget3d(Transform3d pose) {
        this.m_cameraToTarget3d = pose;
    }

    public Mat getCameraRelativeTvec() {
        return m_cameraRelativeTvec;
    }

    public void setCameraRelativeTvec(Mat cameraRelativeTvec) {
        if (this.m_cameraRelativeTvec == null) m_cameraRelativeTvec = new Mat();
        cameraRelativeTvec.copyTo(this.m_cameraRelativeTvec);
    }

    public Mat getCameraRelativeRvec() {
        return m_cameraRelativeRvec;
    }

    public void setCameraRelativeRvec(Mat cameraRelativeRvec) {
        if (this.m_cameraRelativeRvec == null) m_cameraRelativeRvec = new Mat();
        cameraRelativeRvec.copyTo(this.m_cameraRelativeRvec);
    }

    public CVShape getShape() {
        return m_shape;
    }

    public void setShape(CVShape shape) {
        this.m_shape = shape;
    }

    public HashMap<String, Object> toHashMap() {
        var ret = new HashMap<String, Object>();
        ret.put("pitch", getPitch());
        ret.put("yaw", getYaw());
        ret.put("skew", getSkew());
        ret.put("area", getArea());
        ret.put("ambiguity", getPoseAmbiguity());
        if (getCameraToTarget3d() != null) {
            ret.put("pose", transformToMap(getCameraToTarget3d()));
        }
        ret.put("fiducialId", getFiducialId());
        return ret;
    }

    private static HashMap<String, Object> transformToMap(Transform3d transform) {
        var ret = new HashMap<String, Object>();
        ret.put("x", transform.getTranslation().getX());
        ret.put("y", transform.getTranslation().getY());
        ret.put("z", transform.getTranslation().getZ());
        ret.put("qw", transform.getRotation().getQuaternion().getW());
        ret.put("qx", transform.getRotation().getQuaternion().getX());
        ret.put("qy", transform.getRotation().getQuaternion().getY());
        ret.put("qz", transform.getRotation().getQuaternion().getZ());

        ret.put("angle_z", transform.getRotation().getZ() + Math.PI / 2.0);
        return ret;
    }

    public boolean isFiducial() {
        return this.m_fiducialId >= 0;
    }

    public static class TargetCalculationParameters {
        // TargetOffset calculation values
        final boolean isLandscape;
        final TargetOffsetPointEdge targetOffsetPointEdge;

        // RobotOffset calculation values
        final RobotOffsetPointMode robotOffsetPointMode;
        final Point robotOffsetSinglePoint;
        final DualOffsetValues dualOffsetValues;

        // center point of image
        final Point cameraCenterPoint;

        // yaw calculation values
        final double horizontalFocalLength;

        // pitch calculation values
        final double verticalFocalLength;

        // area calculation values
        final double imageArea;

        public TargetCalculationParameters(
                boolean isLandscape,
                TargetOffsetPointEdge targetOffsetPointEdge,
                RobotOffsetPointMode robotOffsetPointMode,
                Point robotOffsetSinglePoint,
                DualOffsetValues dualOffsetValues,
                Point cameraCenterPoint,
                double horizontalFocalLength,
                double verticalFocalLength,
                double imageArea) {

            this.isLandscape = isLandscape;
            this.targetOffsetPointEdge = targetOffsetPointEdge;
            this.robotOffsetPointMode = robotOffsetPointMode;
            this.robotOffsetSinglePoint = robotOffsetSinglePoint;
            this.dualOffsetValues = dualOffsetValues;
            this.cameraCenterPoint = cameraCenterPoint;
            this.horizontalFocalLength = horizontalFocalLength;
            this.verticalFocalLength = verticalFocalLength;
            this.imageArea = imageArea;
        }

        public TargetCalculationParameters(
                boolean isLandscape,
                TargetOffsetPointEdge targetOffsetPointEdge,
                RobotOffsetPointMode robotOffsetPointMode,
                Point robotOffsetSinglePoint,
                DualOffsetValues dualOffsetValues,
                FrameStaticProperties frameStaticProperties) {

            this.isLandscape = isLandscape;
            this.targetOffsetPointEdge = targetOffsetPointEdge;
            this.robotOffsetPointMode = robotOffsetPointMode;
            this.robotOffsetSinglePoint = robotOffsetSinglePoint;
            this.dualOffsetValues = dualOffsetValues;

            this.cameraCenterPoint = frameStaticProperties.centerPoint;
            this.horizontalFocalLength = frameStaticProperties.horizontalFocalLength;
            this.verticalFocalLength = frameStaticProperties.verticalFocalLength;
            this.imageArea = frameStaticProperties.imageArea;
        }
    }
}
