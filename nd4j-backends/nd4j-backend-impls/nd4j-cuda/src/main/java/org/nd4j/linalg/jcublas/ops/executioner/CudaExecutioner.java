/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 *
 */

package org.nd4j.linalg.jcublas.ops.executioner;



import lombok.Getter;
import org.apache.commons.math3.util.Pair;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.ShortPointer;
import org.nd4j.jita.allocator.impl.AllocationPoint;
import org.nd4j.jita.allocator.impl.AtomicAllocator;
import org.nd4j.jita.allocator.tad.DeviceTADManager;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.cache.TADManager;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.complex.IComplexNDArray;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.*;
import org.nd4j.linalg.api.ops.executioner.DefaultOpExecutioner;
import org.nd4j.linalg.api.ops.impl.accum.Variance;
import org.nd4j.linalg.api.ops.impl.transforms.arithmetic.CopyOp;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.buffer.AddressRetriever;
import org.nd4j.linalg.jcublas.context.CudaContext;
import org.nd4j.linalg.util.ArrayUtil;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;


/**
 * JCuda executioner.
 * <p/>
 * Runs ops directly on the gpu
 *
 * If requested Op doesn't exist within GPU context, DefaultOpExecutioner will be used, with arrays/buffers updated after that.
 *
 * @author Adam Gibson
 * @author raver119@gmail.com
 */
public class CudaExecutioner extends DefaultOpExecutioner {

    protected static NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();

//    private static final Allocator allocator = AtomicAllocator.getInstance();
    private static Logger log = LoggerFactory.getLogger(CudaExecutioner.class);

    @Getter protected static TADManager tadManager = new DeviceTADManager();
    protected ThreadLocal<PointerPointer> extraz = new ThreadLocal<>();

    public CudaExecutioner() {

    }

    public NativeOps getNativeOps() {
        return nativeOps;
    }


    @Override
    public INDArray exec(BroadcastOp op,int...dimension) {
        checkForCompression(op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        Arrays.sort(dimension);
//        log.info("B2 OpName: [" + op.getClass().getSimpleName() + "]; OpCode: [" + op.opNum() + "], dimension: {}", Arrays.toString(dimension));

   //     if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
   //         OpDashboard.getInstance().processOpCall(op);

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());


        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer devTadShapeInfoZ = null;
        Pointer devTadOffsetsZ = null;

//        if (!Arrays.equals(op.x().shape(),op.z().shape()) || !Arrays.equals(op.x().stride(),op.z().stride()) || op.x().ordering() != op.z().ordering()) {
            // that's the place where we're going to have second TAD in place
            Pair<DataBuffer, DataBuffer> tadBuffersZ = tadManager.getTADOnlyShapeInfo(op.z(), dimension);

            devTadShapeInfoZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getFirst(), context);
            devTadOffsetsZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getSecond(), context);
//        }

        // extraz.get().put
        // new PointerPointer
        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                hostTadShapeInfo,
                devTadShapeInfo,
                devTadOffsets,
                devTadShapeInfoZ,
                devTadOffsetsZ
        );

        //Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);
        Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context);

        if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            nativeOps.execBroadcastDouble(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (DoublePointer)x,
                    (IntPointer)xShapeInfo,
                    (DoublePointer)y,
                    (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                    (DoublePointer)z,
                    (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                    (IntPointer)dimensionPointer, dimension.length);
        }
        else if(op.x().data().dataType() == DataBuffer.Type.FLOAT) {
            nativeOps.execBroadcastFloat(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (FloatPointer)x,
                    (IntPointer)xShapeInfo,
                    (FloatPointer)y,
                    (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                    (FloatPointer)z,
                    (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                    (IntPointer)dimensionPointer, dimension.length);
        } else {
            nativeOps.execBroadcastHalf(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (ShortPointer)x,
                    (IntPointer)xShapeInfo,
                    (ShortPointer)y,
                    (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                    (ShortPointer)z,
                    (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                    (IntPointer)dimensionPointer, dimension.length);
        }

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        return op.z();
    }

    /**
     *
     * @param op
     * @param dimension
     * @return
     */
    protected INDArray naiveExec(Accumulation op, int... dimension) {

        INDArray ret = op.z();

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = offsets == null ? null :AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                hostTadShapeInfo,
                devTadShapeInfo,
                devTadOffsets
        );


        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(), context) : null;
        //Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(), context) : 0;
        //Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);
        Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context); //AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);


        // log.info("Extras: {}",op.extraArgsDataBuff());
        /*
        log.info("xShapeInfoHostPointer: " + Arrays.toString(xShapeInfoHostPointer));
        log.info("X: " + x);
        log.info("xShapeInfo: " + xShapeInfo);
*/
        if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            if(op instanceof Variance) {
                if(ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    ret.putScalar(0, nativeOps.execSummaryStatsScalarDouble(xShapeInfoHostPointer, op.opNum(), (DoublePointer)x, (IntPointer)xShapeInfo, (DoublePointer)extraArgs, true));

                    op.setFinalResult(ret.getDouble(0));
                } else {
                    nativeOps.execSummaryStatsDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)extraArgs,
                            (DoublePointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length,
                            ((Variance) op).isBiasCorrected()
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            } else if (op.y() != null) {
                if (ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    ret.putScalar(0, nativeOps.execReduce3ScalarDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)extraArgs,
                            (DoublePointer)AtomicAllocator.getInstance().getPointer(op.y(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context)
                    ));

                    op.setFinalResult(ret.getDouble(0));
                } else {
                    nativeOps.execReduce3Double(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)extraArgs,
                            (DoublePointer)AtomicAllocator.getInstance().getPointer(op.y(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                            (DoublePointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            } else {
                if (ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    ret.putScalar(0, nativeOps.execReduceScalarDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)extraArgs
                    ));

                    op.setFinalResult(ret.getDouble(0));
                } else {
                    nativeOps.execReduceDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)extraArgs,
                            (DoublePointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            }
        } else if (op.x().data().dataType() == DataBuffer.Type.FLOAT){
            if(op instanceof Variance) {
                if(ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    ret.putScalar(0, nativeOps.execSummaryStatsScalarFloat(xShapeInfoHostPointer, op.opNum(), (FloatPointer)x, (IntPointer)xShapeInfo, (FloatPointer)extraArgs, true));

                    op.setFinalResult(ret.getFloat(0));
                } else {
                    nativeOps.execSummaryStatsFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)extraArgs,
                            (FloatPointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length,
                            ((Variance) op).isBiasCorrected()
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            } else if (op.y() != null) {
                if (ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    ret.putScalar(0, nativeOps.execReduce3ScalarFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)extraArgs,
                            (FloatPointer)AtomicAllocator.getInstance().getPointer(op.y(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context)
                    ));

                    op.setFinalResult(ret.getFloat(0));
                } else {
                    nativeOps.execReduce3Float(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)extraArgs,
                            (FloatPointer)AtomicAllocator.getInstance().getPointer(op.y(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                            (FloatPointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            } else {
                if (ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    float resx = nativeOps.execReduceScalarFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)extraArgs
                    );

                    ret.putScalar(0, resx);

                    op.setFinalResult(ret.getFloat(0));
                } else {
                    nativeOps.execReduceFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)extraArgs,
                            (FloatPointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            }
        } else {
            if(op instanceof Variance) {
                if(ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    ret.putScalar(0, nativeOps.execSummaryStatsScalarHalf(xShapeInfoHostPointer, op.opNum(), (ShortPointer)x, (IntPointer)xShapeInfo, (ShortPointer)extraArgs, true));

                    op.setFinalResult(ret.getFloat(0));
                } else {
                    nativeOps.execSummaryStatsHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)extraArgs,
                            (ShortPointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length,
                            ((Variance) op).isBiasCorrected()
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            } else if (op.y() != null) {
                if (ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    ret.putScalar(0, nativeOps.execReduce3ScalarHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)extraArgs,
                            (ShortPointer)AtomicAllocator.getInstance().getPointer(op.y(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context)
                    ));

                    op.setFinalResult(ret.getFloat(0));
                } else {
                    nativeOps.execReduce3Half(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)extraArgs,
                            (ShortPointer)AtomicAllocator.getInstance().getPointer(op.y(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                            (ShortPointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            } else {
                if (ret.isScalar()) {
                    AtomicAllocator.getInstance().tickHostWrite(ret);

                    ret.putScalar(0, nativeOps.execReduceScalarHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)extraArgs
                    ));

                    op.setFinalResult(ret.getFloat(0));
                } else {
                    nativeOps.execReduceHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)extraArgs,
                            (ShortPointer)AtomicAllocator.getInstance().getPointer(op.z(), context),
                            (IntPointer)AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer)dimensionPointer,
                            dimension.length
                    );

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            }
        }


        return op.z();
    }

    @Override
    public INDArray exec(Accumulation op, int... dimension) {
        checkForCompression(op);

        Arrays.sort(dimension);

//        if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
//            OpDashboard.getInstance().processOpCall(op);

  //      log.info("A2 OpName: [" + op.getClass().getSimpleName() + "]; OpCode: [" + op.opNum() + "]");
//
//        log.info("op.x shape: " + Arrays.toString(op.x().shape()));
        for(int i = 0; i < dimension.length; i++) {
            if(dimension[i] < 0)
                dimension[i] += op.x().rank();
        }
        //do op along all dimensions
        if (dimension.length == op.x().rank())
            dimension = new int[]{Integer.MAX_VALUE};


        int[] retShape = Shape.wholeArrayDimension(dimension) ? new int[] {1,1} : ArrayUtil.removeIndex(op.x().shape(), dimension);
        //ensure vector is proper shape
        if (retShape.length == 1) {
            if (dimension[0] == 0)
                retShape = new int[]{1, retShape[0]};
            else
                retShape = new int[]{retShape[0], 1};
        } else if (retShape.length == 0) {
            retShape = new int[]{1, 1};
        }

        if(op.x().isVector() && op.x().length() == ArrayUtil.prod(retShape))
            return op.noOp();

        INDArray ret = null;
        if (op.zeroDouble() > -0.01f && op.zeroDouble() < 0.01f) {
            ret= Nd4j.zeros(retShape);
        } else {
            ret = Nd4j.valueArrayOf(retShape, op.zeroDouble());
        }
        op.setZ(ret);

        naiveExec(op, dimension);

        return op.z();
    }

    @Override
    public INDArray exec(IndexAccumulation op, int... dimension) {
        checkForCompression(op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        Arrays.sort(dimension);

        //log.info("OpName: [" + op.getClass().getSimpleName() + "]; OpCode: [" + op.opNum() + "]");

//        if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
//            OpDashboard.getInstance().processOpCall(op);

        for(int i = 0; i < dimension.length; i++) {
            if(dimension[i] < 0)
                dimension[i] += op.x().rank();
        }
        //do op along all dimensions
        if (dimension.length == op.x().rank())
            dimension = new int[]{Integer.MAX_VALUE};



        int[] retShape = Shape.wholeArrayDimension(dimension) ? new int[] {1,1} : ArrayUtil.removeIndex(op.x().shape(), dimension);


        if(op.x().isVector() && op.x().length() == ArrayUtil.prod(retShape)) {
            return op.x();
        }


        //ensure vector is proper shape
        if (retShape.length == 1) {
            if (dimension[0] == 0)
                retShape = new int[]{1, retShape[0]};
            else
                retShape = new int[]{retShape[0], 1};
        } else if (retShape.length == 0) {
            retShape = new int[]{1, 1};
        }

        INDArray ret = null;
        if (op.zeroDouble() > -0.01f && op.zeroDouble() < 0.01f) {
            ret = Nd4j.zeros(retShape);
        } else {
            ret = Nd4j.valueArrayOf(retShape, op.zeroDouble());
        }

        op.setZ(ret);
        //do op along all dimensions
        if (dimension.length == op.x().rank())
            dimension = new int[]{Integer.MAX_VALUE};

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);

        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = offsets == null ? null :AtomicAllocator.getInstance().getPointer(offsets, context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                hostTadShapeInfo,
                devTadShapeInfo,
                devTadOffsets
        );
        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(), context) : null;
        //Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);
        Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context);

        if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            nativeOps.execIndexReduceDouble(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (DoublePointer)x,
                    (IntPointer)xShapeInfo,
                    (DoublePointer)extraArgs,
                    (DoublePointer)z,
                    (IntPointer)zShapeInfo,
                    (IntPointer)dimensionPointer, dimension.length);

        } else if (op.x().data().dataType() == DataBuffer.Type.FLOAT){
            nativeOps.execIndexReduceFloat(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (FloatPointer)x,
                    (IntPointer)xShapeInfo,
                    (FloatPointer)extraArgs,
                    (FloatPointer)z,
                    (IntPointer)zShapeInfo,
                    (IntPointer)dimensionPointer, dimension.length);

        }
        else {
            nativeOps.execIndexReduceHalf(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (ShortPointer)x,
                    (IntPointer)xShapeInfo,
                    (ShortPointer)extraArgs,
                    (ShortPointer)z,
                    (IntPointer)zShapeInfo,
                    (IntPointer)dimensionPointer, dimension.length);

        }

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        return op.z();
    }


    @Override
    public Op exec(Op op, int... dimension) {
        checkForCompression(op);

        Arrays.sort(dimension);
        return super.exec(op, dimension);
    }


    @Override
    public Op exec(Op op) {
        checkForCompression(op);

        //linear views and oblong offsets can't be handled by the gpu (due to the way the buffers are interpreted as vectors)
        if(op.x() instanceof IComplexNDArray || executionMode() == ExecutionMode.JAVA  || op instanceof CopyOp) {
                // we dont' care about op.Z sync state, since it'll be overwritten
                if (op.x() != null)
                    AtomicAllocator.getInstance().synchronizeHostData(op.x());
                if (op.y() != null)
                    AtomicAllocator.getInstance().synchronizeHostData(op.y());

                super.exec(op);

                if (op.z() != null)
                    AtomicAllocator.getInstance().tickHostWrite(op.z());
                return null;
        }

        if (op instanceof TransformOp) {
            TransformOp t = (TransformOp) op;
            invoke(t);
        } else if (op instanceof Accumulation) {
            Accumulation acc = (Accumulation) op;
            invoke(acc,null);
        } else if (op instanceof ScalarOp) {
            ScalarOp sc = (ScalarOp) op;
            invoke(sc);
        } else if(op instanceof BroadcastOp) {
            BroadcastOp broadcastOp = (BroadcastOp) op;
            invoke(broadcastOp);
        }
        else if(op instanceof IndexAccumulation) {
            IndexAccumulation indexAccumulation = (IndexAccumulation) op;
            invoke(indexAccumulation,null);
        }
        return op;
    }



    @Override
    public INDArray execAndReturn(TransformOp op) {
        checkForCompression(op);

        invoke(op);
        return op.z();
    }







    protected CudaContext invoke(BroadcastOp op) {
        checkForCompression(op);
//        if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
//            OpDashboard.getInstance().processOpCall(op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

     //   log.info("B1 OpName: [" + op.getClass().getSimpleName() + "]; OpCode: [" + op.opNum() + "]");
        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);

        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), op.getDimension());

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer devTadShapeInfoZ = null;
        Pointer devTadOffsetsZ = null;

//        if (!Arrays.equals(op.x().shape(),op.z().shape()) || !Arrays.equals(op.x().stride(),op.z().stride()) || op.x().ordering() != op.z().ordering()) {
        // that's the place where we're going to have second TAD in place
        Pair<DataBuffer, DataBuffer> tadBuffersZ = tadManager.getTADOnlyShapeInfo(op.z(), op.getDimension());

        devTadShapeInfoZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getFirst(), context);
        devTadOffsetsZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getSecond(), context);
//        }

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                hostTadShapeInfo,
                devTadShapeInfo,
                devTadOffsets,
                devTadShapeInfoZ,
                devTadOffsetsZ
        );

        Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
        Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);

        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);
        //long dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(op.getDimension()), context);
        Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(op.getDimension()), context);

        if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            nativeOps.execBroadcastDouble(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (DoublePointer)x,
                    (IntPointer)xShapeInfo,
                    (DoublePointer)y,
                    (IntPointer)yShapeInfo,
                    (DoublePointer)z,
                    (IntPointer)zShapeInfo,
                    (IntPointer)dimensionPointer,
                    op.getDimension().length);
        }
        else if (op.x().data().dataType() == DataBuffer.Type.FLOAT){
            nativeOps.execBroadcastFloat(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (FloatPointer)x,
                    (IntPointer)xShapeInfo,
                    (FloatPointer)y,
                    (IntPointer)yShapeInfo,
                    (FloatPointer)z,
                    (IntPointer)zShapeInfo,
                    (IntPointer)dimensionPointer,
                    op.getDimension().length);

        } else {
            nativeOps.execBroadcastHalf(
                    xShapeInfoHostPointer,
                    op.opNum(),
                    (ShortPointer)x,
                    (IntPointer)xShapeInfo,
                    (ShortPointer)y,
                    (IntPointer)yShapeInfo,
                    (ShortPointer)z,
                    (IntPointer)zShapeInfo,
                    (IntPointer)dimensionPointer,
                    op.getDimension().length);
        }

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        return null;
    }



    protected CudaContext invoke(IndexAccumulation op,int[] dimension)  {
        checkForCompression(op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

//        if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
//            OpDashboard.getInstance().processOpCall(op);

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

//        log.info("OpName: [" + op.getClass().getSimpleName() + "]; OpCode: [" + op.opNum() + "]");
        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);
        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(), context) : null;

        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        int fdimension[] = dimension;
        if (fdimension == null)
            fdimension = new int[] {0};

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), fdimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = offsets == null ? null :AtomicAllocator.getInstance().getPointer(offsets, context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                hostTadShapeInfo,
                devTadShapeInfo,
                devTadOffsets
        );

      //  System.out.println("X shapeInfo host address: " + xShapeInfoHostPointer[0]);
        if(op.z().isScalar() || dimension == null || dimension[0] == Integer.MAX_VALUE) {
            if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                double result = nativeOps.execIndexReduceScalarDouble(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (DoublePointer)x,
                        (IntPointer)xShapeInfo,
                        (DoublePointer)extraArgs);
                op.setFinalResult((int) result);
            } else if (op.x().data().dataType() == DataBuffer.Type.FLOAT) {
                float result = nativeOps.execIndexReduceScalarFloat(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (FloatPointer)x,
                        (IntPointer)xShapeInfo,
                        (FloatPointer)extraArgs);
                op.setFinalResult((int) result);
            }
            else {
                float result = nativeOps.execIndexReduceScalarHalf(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (ShortPointer)x,
                        (IntPointer)xShapeInfo,
                        (ShortPointer)extraArgs);
                op.setFinalResult((int) result);
            }
        }
        else {
            Arrays.sort(dimension);

            Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
            Pointer zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);
            //long dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);
            Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context);

//            log.info("Z.length: " + op.z().length());
//            log.info("Z.shapeInfo: " + op.z().shapeInfoDataBuffer());

            if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                nativeOps.execIndexReduceDouble(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (DoublePointer)x,
                        (IntPointer)xShapeInfo,
                        (DoublePointer)extraArgs,
                        (DoublePointer)z,
                        (IntPointer)zShapeInfo,
                        (IntPointer)dimensionPointer,
                        dimension.length);
            } else  if (op.x().data().dataType() == DataBuffer.Type.FLOAT) {
                nativeOps.execIndexReduceFloat(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (FloatPointer)x,
                        (IntPointer)xShapeInfo,
                        (FloatPointer)extraArgs,
                        (FloatPointer)z,
                        (IntPointer)zShapeInfo,
                        (IntPointer)dimensionPointer,
                        dimension.length);
            }
            else {
                nativeOps.execIndexReduceHalf(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (ShortPointer)x,
                        (IntPointer)xShapeInfo,
                        (ShortPointer)extraArgs,
                        (ShortPointer)z,
                        (IntPointer)zShapeInfo,
                        (IntPointer)dimensionPointer,
                        dimension.length);
            }
        }

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        return null;

    }


    protected CudaContext invoke(Accumulation op, int[] dimension) {
        checkForCompression(op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

      //  log.info("A OpName: [" + op.getClass().getSimpleName() + "]; OpCode: [" + op.opNum() + "]");
        // dimension is ALWAYS null here.
        if (dimension == null)
            dimension = new int[] {Integer.MAX_VALUE};

//        if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
//            OpDashboard.getInstance().processOpCall(op);

        Arrays.sort(dimension);

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = offsets == null ? null :AtomicAllocator.getInstance().getPointer(offsets, context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                hostTadShapeInfo,
                devTadShapeInfo,
                devTadOffsets
        );
        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);
        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(), context) : null;

        int[] retShape = Shape.wholeArrayDimension(dimension) ? new int[] {1,1} : ArrayUtil.removeIndex(op.x().shape(), dimension);
        //ensure vector is proper shape
        if (retShape.length == 1) {
            if (dimension[0] == 0)
                retShape = new int[]{1, retShape[0]};
            else
                retShape = new int[]{retShape[0], 1};
        } else if (retShape.length == 0) {
            retShape = new int[]{1, 1};
        }

        if(op.x().isVector() && op.x().length() == ArrayUtil.prod(retShape))
            return null;

        INDArray ret = null;
        if (op.zeroDouble() > -0.01f && op.zeroDouble() < 0.01f) {
            ret= Nd4j.zeros(retShape);
        } else {
            ret = Nd4j.valueArrayOf(retShape, op.zeroDouble());
        }
        op.setZ(ret);

        if(op.z().isScalar()) {
            if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                if(op instanceof Variance) {
                    double result = nativeOps.execSummaryStatsScalarDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo, (DoublePointer)extraArgs, true);
                    op.setFinalResult(result);
                } else if (op.y() != null) {
                    Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
                    Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);
                    double result = nativeOps.execReduce3ScalarDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)extraArgs,
                            (DoublePointer)y,
                            (IntPointer)yShapeInfo);
                    op.setFinalResult(result);
                } else {
                    double result = nativeOps.execReduceScalarDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)extraArgs);
                    op.setFinalResult(result);
                }
            } else if (op.x().data().dataType() == DataBuffer.Type.FLOAT) {
                if(op instanceof Variance) {
                    float result = nativeOps.execSummaryStatsScalarFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo, (FloatPointer)extraArgs, true);
                    op.setFinalResult(result);
                } else if (op.y() != null) {
                    Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
                    Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);

                    float result = nativeOps.execReduce3ScalarFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)extraArgs,
                            (FloatPointer)y,
                            (IntPointer)yShapeInfo);
                    op.setFinalResult(result);
                } else {
                    float result = nativeOps.execReduceScalarFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)extraArgs);
                    op.setFinalResult(result);
                }
            } else {
                if(op instanceof Variance) {
                    float result = nativeOps.execSummaryStatsScalarHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo, (ShortPointer)extraArgs, true);
                    op.setFinalResult(result);
                } else if (op.y() != null) {
                    Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
                    Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);

                    float result = nativeOps.execReduce3ScalarHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)extraArgs,
                            (ShortPointer)y,
                            (IntPointer)yShapeInfo);
                    op.setFinalResult(result);
                } else {
                    float result = nativeOps.execReduceScalarHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)extraArgs);
                    op.setFinalResult(result);
                }
            }

        }
        else {
            Pointer result = AtomicAllocator.getInstance().getPointer(op.z(), context);
            Pointer resultShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);
            Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context); //AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);

            if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                if(op.y() != null) {
                    Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
                    Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);
                    nativeOps.execReduce3Double(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)extraArgs,
                            (DoublePointer)y,
                            (IntPointer)yShapeInfo,
                            (DoublePointer)result,
                            (IntPointer)resultShapeInfo,
                            (IntPointer)dimensionPointer,
                            dimension.length);
                }
                else {
                    if(op instanceof Variance) {
                       nativeOps.execSummaryStatsDouble(
                               xShapeInfoHostPointer,
                               op.opNum(),
                               (DoublePointer)x,
                               (IntPointer)xShapeInfo,
                               (DoublePointer)extraArgs,
                               (DoublePointer)result,
                               (IntPointer)resultShapeInfo,
                               (IntPointer)dimensionPointer,
                               dimension.length,
                               ((Variance) op).isBiasCorrected());
                    }
                    else {
                        nativeOps.execReduceDouble(
                                xShapeInfoHostPointer,
                                op.opNum(),
                                (DoublePointer)x,
                                (IntPointer)xShapeInfo,
                                (DoublePointer)extraArgs,
                                (DoublePointer)result,
                                (IntPointer)resultShapeInfo,
                                (IntPointer)dimensionPointer,
                                dimension.length);
                    }
                }

            }
            //float
            else if(op.x().data().dataType() == DataBuffer.Type.FLOAT)  {
                if(op.y() != null) {
                    Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
                    Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);
                    nativeOps.execReduce3Float(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)extraArgs,
                            (FloatPointer)y,
                            (IntPointer)yShapeInfo,
                            (FloatPointer)result,
                            (IntPointer)resultShapeInfo,
                            (IntPointer)dimensionPointer,
                            dimension.length);

                }
                else {

                    if(op instanceof Variance) {
                        nativeOps.execSummaryStatsFloat(
                                xShapeInfoHostPointer,
                                op.opNum(),
                                (FloatPointer)x,
                                (IntPointer)xShapeInfo,
                                (FloatPointer)extraArgs,
                                (FloatPointer)result,
                                (IntPointer)resultShapeInfo,
                                (IntPointer)dimensionPointer,
                                dimension.length,
                                ((Variance) op).isBiasCorrected());
                    }
                    else {
                        nativeOps.execReduceFloat(
                                xShapeInfoHostPointer,
                                op.opNum(),
                                (FloatPointer)x,
                                (IntPointer)xShapeInfo,
                                (FloatPointer)extraArgs,
                                (FloatPointer)result,
                                (IntPointer)resultShapeInfo,
                                (IntPointer)dimensionPointer,
                                dimension.length);
                    }
                }
            } // Half
            else {
                if(op.y() != null) {
                    Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
                    Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);
                    nativeOps.execReduce3Half(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)extraArgs,
                            (ShortPointer)y,
                            (IntPointer)yShapeInfo,
                            (ShortPointer)result,
                            (IntPointer)resultShapeInfo,
                            (IntPointer)dimensionPointer,
                            dimension.length);

                }
                else {

                    if(op instanceof Variance) {
                        nativeOps.execSummaryStatsHalf(
                                xShapeInfoHostPointer,
                                op.opNum(),
                                (ShortPointer)x,
                                (IntPointer)xShapeInfo,
                                (ShortPointer)extraArgs,
                                (ShortPointer)result,
                                (IntPointer)resultShapeInfo,
                                (IntPointer)dimensionPointer,
                                dimension.length,
                                ((Variance) op).isBiasCorrected());
                    }
                    else {
                        nativeOps.execReduceHalf(
                                xShapeInfoHostPointer,
                                op.opNum(),
                                (ShortPointer)x,
                                (IntPointer)xShapeInfo,
                                (ShortPointer)extraArgs,
                                (ShortPointer)result,
                                (IntPointer)resultShapeInfo,
                                (IntPointer)dimensionPointer,
                                dimension.length);
                    }
                }
            }

        }

//&& !op.z().isScalar()
        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        return context;
    }


    protected CudaContext intercept(ScalarOp op, int[] dimension) {

        Arrays.sort(dimension);
//      log.info("S3 OpName: [" + op.getClass().getSimpleName() + "]; OpCode: [" + op.opNum() + "], dimension: {}", Arrays.toString(dimension));

        //     if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
        //         OpDashboard.getInstance().processOpCall(op);

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());


        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);
        Pointer zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer devTadShapeInfoZ = null;
        Pointer devTadOffsetsZ = null;

        Pair<DataBuffer, DataBuffer> tadBuffersZ = tadManager.getTADOnlyShapeInfo(op.z(), dimension);

        devTadShapeInfoZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getFirst(), context);
        devTadOffsetsZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getSecond(), context);


        PointerPointer extraPointers = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                hostTadShapeInfo,
                devTadShapeInfo,
                devTadOffsets,
                devTadShapeInfoZ,
                devTadOffsetsZ
        );

        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(), context) : null;

        Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context);


        if (op.x().data().dataType() == DataBuffer.Type.HALF) {
            nativeOps.execScalarHalf(extraPointers,
                    op.opNum(),
                    (ShortPointer) x,
                    (IntPointer) xShapeInfo,
                    (ShortPointer)z,
                    (IntPointer)zShapeInfo,
                    (ShortPointer) y,
                    (ShortPointer) extraArgs,
                    (IntPointer) dimensionPointer,
                    dimension.length
                    );
        } else if (op.x().data().dataType() == DataBuffer.Type.FLOAT) {
            nativeOps.execScalarFloat(extraPointers,
                    op.opNum(),
                    (FloatPointer) x,
                    (IntPointer) xShapeInfo,
                    (FloatPointer)z,
                    (IntPointer)zShapeInfo,
                    (FloatPointer) y,
                    (FloatPointer) extraArgs,
                    (IntPointer) dimensionPointer,
                    dimension.length
            );
        } else if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            nativeOps.execScalarDouble(extraPointers,
                    op.opNum(),
                    (DoublePointer) x,
                    (IntPointer) xShapeInfo,
                    (DoublePointer)z,
                    (IntPointer)zShapeInfo,
                    (DoublePointer) y,
                    (DoublePointer) extraArgs,
                    (IntPointer) dimensionPointer,
                    dimension.length
            );
        }

        AtomicAllocator.getInstance().getFlowController().registerAction(context, op.z(), op.x(), op.y());

        return null;
    }

    protected CudaContext invoke(ScalarOp op) {
        checkForCompression(op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        if (op.getDimension() != null) {
            intercept(op, op.getDimension());
            return null;
        }

//        if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
//            OpDashboard.getInstance().processOpCall(op);

      //  log.info("OpName: [" + op.getClass().getSimpleName() + "]; OpCode: [" + op.opNum() + "]");

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);
        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(), context) : null;

        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                null,
                null
        );

        if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            if (op.x().elementWiseStride() >= 1 && op.z().ordering() == op.x().ordering()) {
                nativeOps.execScalarDouble(xShapeInfoHostPointer,
                        op.opNum(),
                        (DoublePointer) x,
                        op.x().elementWiseStride(),
                        (DoublePointer) z,
                        op.z().elementWiseStride(),
                        op.scalar().doubleValue(),
                        (DoublePointer) extraArgs,
                        op.n());
            } else {
                nativeOps.execScalarDouble(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (DoublePointer) x,
                        (IntPointer) xShapeInfo,
                        (DoublePointer) z,
                        (IntPointer) zShapeInfo,
                        op.scalar().doubleValue(),
                        (DoublePointer) extraArgs);
            }
        }
        else if (op.x().data().dataType() == DataBuffer.Type.FLOAT) {
            if (op.x().elementWiseStride() >= 1 && op.z().ordering() == op.x().ordering()) {
                nativeOps.execScalarFloat(xShapeInfoHostPointer,
                        op.opNum(),
                        (FloatPointer) x,
                        op.x().elementWiseStride(),
                        (FloatPointer) z,
                        op.z().elementWiseStride(),
                        op.scalar().floatValue(),
                        (FloatPointer) extraArgs,
                        op.n());
            } else {
                nativeOps.execScalarFloat(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (FloatPointer) x,
                        (IntPointer) xShapeInfo,
                        (FloatPointer) z,
                        (IntPointer) zShapeInfo,
                        op.scalar().floatValue(),
                        (FloatPointer) extraArgs);
            }
        } else {
            if (op.x().elementWiseStride() >= 1 && op.z().ordering() == op.x().ordering()) {
                nativeOps.execScalarHalf(xShapeInfoHostPointer,
                        op.opNum(),
                        (ShortPointer) x,
                        op.x().elementWiseStride(),
                        (ShortPointer) z,
                        op.z().elementWiseStride(),
                        op.scalar().floatValue(),
                        (ShortPointer) extraArgs,
                        op.n());
            } else {
                nativeOps.execScalarHalf(
                        xShapeInfoHostPointer,
                        op.opNum(),
                        (ShortPointer) x,
                        (IntPointer) xShapeInfo,
                        (ShortPointer) z,
                        (IntPointer) zShapeInfo,
                        op.scalar().floatValue(),
                        (ShortPointer) extraArgs);
            }
        }

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        return  null;
    }

    protected CudaContext invoke(TransformOp op) {
        checkForCompression(op);

        AtomicAllocator allocator = AtomicAllocator.getInstance();

        // this is special case for assign
/*
        if (op.opNum() == 16 && op.y() != null && !op.y().isView() && !op.x().isView() && !op.z().isView()
                && op.z().ordering() == op.y().ordering() && op.y().ordering() == op.x().ordering()
                && Arrays.equals(op.y().shape(), op.z().shape()) && Arrays.equals(op.y().stride(), op.z().stride())
                ) {
            AllocationPoint point = allocator.getAllocationPoint(op.y());
            AllocationPoint pointDst = allocator.getAllocationPoint(op.z());
            synchronized (point) {
//                log.info("X: {}; Y: {}, Z: {}", op.x().ordering(), op.y().ordering(), op.z().ordering());
                CudaContext context = (CudaContext) allocator.getDeviceContext().getContext();

                allocator.memcpyDevice(op.z().data(), allocator.getPointer(op.y(), context), op.y().length() * op.y().data().getElementSize(), 0, context);
                context.syncOldStream();

                return null;
            }
        }
*/

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        //if (CudaEnvironment.getInstance().getConfiguration().isGatherStatistics())
//            OpDashboard.getInstance().processOpCall(op);

//        log.info("T OpName: [" + op.getClass().getCanonicalName() + "]; OpCode: [" + op.opNum() + "]");

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        // special temp array for IsMax along dimension
        INDArray ret = null;

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);
        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(), context) : null;


        Pointer hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pointer dimensionDevPointer = null;
        Pointer dimensionHostPointer = null;
        Pointer retPointer = null;
        int dimension[] = null;

        if (op.opNum() == 41 && op.extraArgs() != null) {
            // for IsMax along dimension we need special temporary buffer
            dimension = new int[] {(int) op.extraArgs()[1] };
            for(int i = 0; i < dimension.length; i++) {
                if(dimension[i] < 0)
                    dimension[i] += op.x().rank();
            }
            //do op along all dimensions
            if (dimension.length == op.x().rank())
                dimension = new int[]{Integer.MAX_VALUE};

            int[] retShape = Shape.wholeArrayDimension(dimension) ? new int[] {1,1} : ArrayUtil.removeIndex(op.x().shape(), dimension);

            //ensure vector is proper shape
            if (retShape.length == 1) {
                if (dimension[0] == 0)
                    retShape = new int[]{1, retShape[0]};
                else
                    retShape = new int[]{retShape[0], 1};
            } else if (retShape.length == 0) {
                retShape = new int[]{1, 1};
            }

            ret = Nd4j.zeros(retShape);

            //log.info("Intermediatery result buffer: {}", ret.shapeInfoDataBuffer());
            //log.info("Result buffer length: {}", ret.length());

            // FIXME: this maybe misleading use of this particular pointer
            hostYShapeInfo = AtomicAllocator.getInstance().getPointer(ret.shapeInfoDataBuffer(), context);

            //dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);
            DataBuffer dimensionBuffer = AtomicAllocator.getInstance().getConstantBuffer(dimension);
            dimensionDevPointer = AtomicAllocator.getInstance().getPointer(dimensionBuffer, context);
            dimensionHostPointer = AtomicAllocator.getInstance().getHostPointer(dimensionBuffer);

            retPointer = AtomicAllocator.getInstance().getPointer(ret, context);
        }

        Pointer hostTadShapeInfo = null;
        Pointer devTadShapeInfo = null;

        Pointer hostMaxTadShapeInfo = null;
        Pointer devMaxTadShapeInfo = null;

        Pair<DataBuffer, DataBuffer> tadBuffers;
        Pair<DataBuffer, DataBuffer> tadMaxBuffers;

        Pointer devTadOffsets = null;
        Pointer devMaxTadOffsets = null;

        if (op.opNum() >= 38 && op.opNum() <= 41) {

            if (op.opNum() != 41) {
                tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), new int[]{0});
                tadMaxBuffers = tadManager.getTADOnlyShapeInfo(op.x(), new int[]{1});

                hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
                devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

                hostMaxTadShapeInfo = AddressRetriever.retrieveHostPointer(tadMaxBuffers.getFirst());
                devMaxTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadMaxBuffers.getFirst(), context);

                DataBuffer offsets = tadBuffers.getSecond();
                devTadOffsets = offsets == null ? null : AtomicAllocator.getInstance().getPointer(offsets, context);

                DataBuffer maxOffsets = tadMaxBuffers.getSecond();
                devMaxTadOffsets = maxOffsets == null ? null : AtomicAllocator.getInstance().getPointer(maxOffsets, context);
            } else {
                tadBuffers = tadManager.getTADOnlyShapeInfo(op.z(), dimension);

                hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
                devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

                DataBuffer offsets = tadBuffers.getSecond();
                devTadOffsets = offsets == null ? null : AtomicAllocator.getInstance().getPointer(offsets, context);

             //   log.info("offsets length: {}", offsets.length());
           //     log.info("TAD shapeInfo on Java size: {}", tadBuffers.getFirst());
                //throw new RuntimeException();
            }
        }

        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),  // 0
                context.getOldStream(),      // 1
                AtomicAllocator.getInstance().getDeviceIdPointer(),        // 2
                context.getBufferAllocation(),      // 3
                context.getBufferReduction(),   // 4
                context.getBufferScalar(),      // 5
                context.getBufferSpecial(),     // 6
                hostYShapeInfo,         // 7
                hostZShapeInfo,         // 8
                hostTadShapeInfo,       // 9
                devTadShapeInfo,        // 10
                devTadOffsets,              // 11
                hostMaxTadShapeInfo,        // 12
                devMaxTadShapeInfo,     // 13
                devMaxTadOffsets, // 14
                dimensionDevPointer, // special pointer for IsMax  // 15
                dimensionHostPointer, // special pointer for IsMax  // 16
                retPointer // special pointer for IsMax // 17
        );


/*
        log.info("------------------------------------");
        log.info("xShapeInfoHostPointer: " + Arrays.toString(xShapeInfoHostPointer));
        log.info("X: {}, Y: {}, Z: {}", x, op.y() != null ? AtomicAllocator.getInstance().getPointer(op.y()) : null, z);
        log.info("xShapeInfo: " + xShapeInfo);
*/
        if(op.y() != null) {
            Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
            Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);
/*
            log.info("X shapeInfo: " + op.x().shapeInfoDataBuffer());
            log.info("Y shapeInfo: " + op.y().shapeInfoDataBuffer());
            log.info("Z shapeInfo: " + op.z().shapeInfoDataBuffer());
*/

            if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                if(op.x().elementWiseStride() >=1 && op.y().elementWiseStride() >= 1 && !op.isExecSpecial() && op.x().ordering() == op.y().ordering() && op.x().ordering() == op.z().ordering()) {

                    nativeOps.execPairwiseTransformDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            op.x().elementWiseStride(),
                            (DoublePointer)y,
                            op.y().elementWiseStride(),
                            (DoublePointer)z,
                            op.z().elementWiseStride(),
                            (DoublePointer)extraArgs,
                            op.n()
                    );
                } else {
                    nativeOps.execPairwiseTransformDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)y,
                            (IntPointer)yShapeInfo,
                            (DoublePointer)z,
                            (IntPointer)zShapeInfo,
                            (DoublePointer)extraArgs);
                }
            } else if (op.x().data().dataType() == DataBuffer.Type.FLOAT) {
                if(op.x().elementWiseStride() >=1 && op.y().elementWiseStride() >= 1 && op.x().elementWiseStride() == op.y(). elementWiseStride() && !op.isExecSpecial() && op.x().ordering() == op.y().ordering() && op.x().ordering() == op.z().ordering()) {
                    nativeOps.execPairwiseTransformFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            op.x().elementWiseStride(),
                            (FloatPointer)y,
                            op.y().elementWiseStride(),
                            (FloatPointer)z,
                            op.z().elementWiseStride(),
                            (FloatPointer)extraArgs,
                            op.n()
                    );
                } else {
                    nativeOps.execPairwiseTransformFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)y,
                            (IntPointer)yShapeInfo,
                            (FloatPointer)z,
                            (IntPointer)zShapeInfo,
                            (FloatPointer)extraArgs);
                }
            } else {
                if(op.x().elementWiseStride() >=1 && op.y().elementWiseStride() >= 1 && op.x().elementWiseStride() == op.y(). elementWiseStride() && !op.isExecSpecial() && op.x().ordering() == op.y().ordering() && op.x().ordering() == op.z().ordering()) {
                    nativeOps.execPairwiseTransformHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            op.x().elementWiseStride(),
                            (ShortPointer)y,
                            op.y().elementWiseStride(),
                            (ShortPointer)z,
                            op.z().elementWiseStride(),
                            (ShortPointer)extraArgs,
                            op.n()
                    );
                } else {
                    nativeOps.execPairwiseTransformHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)y,
                            (IntPointer)yShapeInfo,
                            (ShortPointer)z,
                            (IntPointer)zShapeInfo,
                            (ShortPointer)extraArgs);
                }
            }
        }
        else {
            if(op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                if(op.x(). elementWiseStride() >= 1 && !op.isExecSpecial() && op.z().ordering() == op.x().ordering()) {
                    nativeOps.execTransformDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            op.x().elementWiseStride(),
                            (DoublePointer)z,
                            op.z().elementWiseStride(),
                            (DoublePointer)extraArgs,
                            op.n()
                    );
                } else {
                    nativeOps.execTransformDouble(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (DoublePointer)x,
                            (IntPointer)xShapeInfo,
                            (DoublePointer)z,
                            (IntPointer)zShapeInfo,
                            (DoublePointer)extraArgs);
                }
            } else if(op.x().data().dataType() == DataBuffer.Type.FLOAT) {
                if(op.x(). elementWiseStride() >= 1 && !op.isExecSpecial() && op.z().ordering() == op.x().ordering()) {
                    nativeOps.execTransformFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            op.x().elementWiseStride(),
                            (FloatPointer)z,
                            op.z().elementWiseStride(),
                            (FloatPointer)extraArgs,
                            op.n()
                    );
                } else {
                    nativeOps.execTransformFloat(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (FloatPointer)x,
                            (IntPointer)xShapeInfo,
                            (FloatPointer)z,
                            (IntPointer)zShapeInfo,
                            (FloatPointer)extraArgs);
                }
            } else {
                if(op.x(). elementWiseStride() >= 1 && !op.isExecSpecial() && op.z().ordering() == op.x().ordering()) {
                    nativeOps.execTransformHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            op.x().elementWiseStride(),
                            (ShortPointer)z,
                            op.z().elementWiseStride(),
                            (ShortPointer)extraArgs,
                            op.n()
                    );
                } else {
                    nativeOps.execTransformHalf(
                            xShapeInfoHostPointer,
                            op.opNum(),
                            (ShortPointer)x,
                            (IntPointer)xShapeInfo,
                            (ShortPointer)z,
                            (IntPointer)zShapeInfo,
                            (ShortPointer)extraArgs);
                }
            }
        }


        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        if (extraArgs != null)
            extraArgs.address();

        if (ret != null)
            ret.elementWiseStride();

        return null;
    }
}


