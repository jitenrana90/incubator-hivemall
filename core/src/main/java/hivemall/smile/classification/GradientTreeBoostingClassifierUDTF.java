/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package hivemall.smile.classification;

import hivemall.UDTFWithOptions;
import hivemall.math.matrix.Matrix;
import hivemall.math.matrix.builders.CSRMatrixBuilder;
import hivemall.math.matrix.builders.MatrixBuilder;
import hivemall.math.matrix.builders.RowMajorDenseMatrixBuilder;
import hivemall.math.matrix.ints.ColumnMajorIntMatrix;
import hivemall.math.random.PRNG;
import hivemall.math.random.RandomNumberGeneratorFactory;
import hivemall.math.vector.Vector;
import hivemall.smile.data.Attribute;
import hivemall.smile.regression.RegressionTree;
import hivemall.smile.utils.SmileExtUtils;
import hivemall.utils.codec.Base91;
import hivemall.utils.collections.lists.IntArrayList;
import hivemall.utils.hadoop.HiveUtils;
import hivemall.utils.hadoop.WritableUtils;
import hivemall.utils.lang.Primitives;
import hivemall.utils.math.MathUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.Counters.Counter;
import org.apache.hadoop.mapred.Reporter;

@Description(name = "train_gradient_tree_boosting_classifier",
        value = "_FUNC_(array<double|string> features, int label [, string options]) - "
                + "Returns a relation consists of "
                + "<int iteration, int model_type, array<string> pred_models, double intercept, "
                + "double shrinkage, array<double> var_importance, float oob_error_rate>")
public final class GradientTreeBoostingClassifierUDTF extends UDTFWithOptions {
    private static final Log logger = LogFactory.getLog(GradientTreeBoostingClassifierUDTF.class);

    private ListObjectInspector featureListOI;
    private PrimitiveObjectInspector featureElemOI;
    private PrimitiveObjectInspector labelOI;

    private boolean denseInput;
    private MatrixBuilder matrixBuilder;
    private IntArrayList labels;
    /**
     * The number of trees for each task
     */
    private int _numTrees;
    /**
     * The learning rate of procedure
     */
    private double _eta;
    /**
     * The sampling rate for stochastic tree boosting
     */
    private double _subsample = 0.7;
    /**
     * The number of random selected features
     */
    private float _numVars;
    /**
     * The maximum number of the tree depth
     */
    private int _maxDepth;
    /**
     * The maximum number of leaf nodes
     */
    private int _maxLeafNodes;
    private int _minSamplesSplit;
    private int _minSamplesLeaf;
    private long _seed;
    private Attribute[] _attributes;

    @Nullable
    private Reporter _progressReporter;
    @Nullable
    private Counter _iterationCounter;

    @Override
    protected Options getOptions() {
        Options opts = new Options();
        opts.addOption("trees", "num_trees", true,
            "The number of trees for each task [default: 500]");
        opts.addOption("eta", "learning_rate", true,
            "The learning rate (0, 1]  of procedure [default: 0.05]");
        opts.addOption("subsample", "sampling_frac", true,
            "The fraction of samples to be used for fitting the individual base learners [default: 0.7]");
        opts.addOption("vars", "num_variables", true,
            "The number of random selected features [default: ceil(sqrt(x[0].length))]."
                    + " int(num_variables * x[0].length) is considered if num_variable is (0,1]");
        opts.addOption("depth", "max_depth", true,
            "The maximum number of the tree depth [default: 8]");
        opts.addOption("leafs", "max_leaf_nodes", true,
            "The maximum number of leaf nodes [default: Integer.MAX_VALUE]");
        opts.addOption("splits", "min_split", true,
            "A node that has greater than or equals to `min_split` examples will split [default: 5]");
        opts.addOption("min_samples_leaf", true,
            "The minimum number of samples in a leaf node [default: 1]");
        opts.addOption("seed", true, "seed value in long [default: -1 (random)]");
        opts.addOption("attrs", "attribute_types", true, "Comma separated attribute types "
                + "(Q for quantitative variable and C for categorical variable. e.g., [Q,C,Q,C])");
        return opts;
    }

    @Override
    protected CommandLine processOptions(ObjectInspector[] argOIs) throws UDFArgumentException {
        int trees = 500, maxDepth = 8;
        int maxLeafs = Integer.MAX_VALUE, minSplit = 5, minSamplesLeaf = 1;
        float numVars = -1.f;
        double eta = 0.05d, subsample = 0.7d;
        Attribute[] attrs = null;
        long seed = -1L;

        CommandLine cl = null;
        if (argOIs.length >= 3) {
            String rawArgs = HiveUtils.getConstString(argOIs[2]);
            cl = parseOptions(rawArgs);

            trees = Primitives.parseInt(cl.getOptionValue("num_trees"), trees);
            if (trees < 1) {
                throw new IllegalArgumentException("Invlaid number of trees: " + trees);
            }
            eta = Primitives.parseDouble(cl.getOptionValue("learning_rate"), eta);
            subsample = Primitives.parseDouble(cl.getOptionValue("subsample"), subsample);
            numVars = Primitives.parseFloat(cl.getOptionValue("num_variables"), numVars);
            maxDepth = Primitives.parseInt(cl.getOptionValue("max_depth"), maxDepth);
            maxLeafs = Primitives.parseInt(cl.getOptionValue("max_leaf_nodes"), maxLeafs);
            minSplit = Primitives.parseInt(cl.getOptionValue("min_split"), minSplit);
            minSamplesLeaf = Primitives.parseInt(cl.getOptionValue("min_samples_leaf"),
                minSamplesLeaf);
            seed = Primitives.parseLong(cl.getOptionValue("seed"), seed);
            attrs = SmileExtUtils.resolveAttributes(cl.getOptionValue("attribute_types"));
        }

        this._numTrees = trees;
        this._eta = eta;
        this._subsample = subsample;
        this._numVars = numVars;
        this._maxDepth = maxDepth;
        this._maxLeafNodes = maxLeafs;
        this._minSamplesSplit = minSplit;
        this._minSamplesLeaf = minSamplesLeaf;
        this._seed = seed;
        this._attributes = attrs;

        return cl;
    }

    @Override
    public StructObjectInspector initialize(ObjectInspector[] argOIs) throws UDFArgumentException {
        if (argOIs.length != 2 && argOIs.length != 3) {
            throw new UDFArgumentException(
                getClass().getSimpleName()
                        + " takes 2 or 3 arguments: array<double|string> features, int label [, const string options]: "
                        + argOIs.length);
        }

        ListObjectInspector listOI = HiveUtils.asListOI(argOIs[0]);
        ObjectInspector elemOI = listOI.getListElementObjectInspector();
        this.featureListOI = listOI;
        if (HiveUtils.isNumberOI(elemOI)) {
            this.featureElemOI = HiveUtils.asDoubleCompatibleOI(elemOI);
            this.denseInput = true;
            this.matrixBuilder = new RowMajorDenseMatrixBuilder(8192);
        } else if (HiveUtils.isStringOI(elemOI)) {
            this.featureElemOI = HiveUtils.asStringOI(elemOI);
            this.denseInput = false;
            this.matrixBuilder = new CSRMatrixBuilder(8192);
        } else {
            throw new UDFArgumentException(
                "_FUNC_ takes double[] or string[] for the first argument: " + listOI.getTypeName());
        }
        this.labelOI = HiveUtils.asIntCompatibleOI(argOIs[1]);

        processOptions(argOIs);

        this.labels = new IntArrayList(1024);

        ArrayList<String> fieldNames = new ArrayList<String>(6);
        ArrayList<ObjectInspector> fieldOIs = new ArrayList<ObjectInspector>(6);

        fieldNames.add("iteration");
        fieldOIs.add(PrimitiveObjectInspectorFactory.writableIntObjectInspector);
        fieldNames.add("pred_models");
        fieldOIs.add(ObjectInspectorFactory.getStandardListObjectInspector(PrimitiveObjectInspectorFactory.writableStringObjectInspector));
        fieldNames.add("intercept");
        fieldOIs.add(PrimitiveObjectInspectorFactory.writableDoubleObjectInspector);
        fieldNames.add("shrinkage");
        fieldOIs.add(PrimitiveObjectInspectorFactory.writableDoubleObjectInspector);
        fieldNames.add("var_importance");
        fieldOIs.add(ObjectInspectorFactory.getStandardListObjectInspector(PrimitiveObjectInspectorFactory.writableDoubleObjectInspector));
        fieldNames.add("oob_error_rate");
        fieldOIs.add(PrimitiveObjectInspectorFactory.writableFloatObjectInspector);

        return ObjectInspectorFactory.getStandardStructObjectInspector(fieldNames, fieldOIs);
    }

    @Override
    public void process(Object[] args) throws HiveException {
        if (args[0] == null) {
            throw new HiveException("array<double> features was null");
        }
        parseFeatures(args[0], matrixBuilder);
        int label = PrimitiveObjectInspectorUtils.getInt(args[1], labelOI);
        labels.add(label);
    }

    private void parseFeatures(@Nonnull final Object argObj, @Nonnull final MatrixBuilder builder) {
        if (denseInput) {
            final int length = featureListOI.getListLength(argObj);
            for (int i = 0; i < length; i++) {
                Object o = featureListOI.getListElement(argObj, i);
                if (o == null) {
                    continue;
                }
                double v = PrimitiveObjectInspectorUtils.getDouble(o, featureElemOI);
                builder.nextColumn(i, v);
            }
        } else {
            final int length = featureListOI.getListLength(argObj);
            for (int i = 0; i < length; i++) {
                Object o = featureListOI.getListElement(argObj, i);
                if (o == null) {
                    continue;
                }
                String fv = o.toString();
                builder.nextColumn(fv);
            }
        }
        builder.nextRow();
    }

    @Override
    public void close() throws HiveException {
        this._progressReporter = getReporter();
        this._iterationCounter = (_progressReporter == null) ? null : _progressReporter.getCounter(
            "hivemall.smile.GradientTreeBoostingClassifier$Counter", "iteration");
        reportProgress(_progressReporter);

        if (!labels.isEmpty()) {
            Matrix x = matrixBuilder.buildMatrix();
            this.matrixBuilder = null;
            int[] y = labels.toArray();
            this.labels = null;

            // run training
            train(x, y);
        }

        // clean up
        this.featureListOI = null;
        this.featureElemOI = null;
        this.labelOI = null;
        this._attributes = null;
    }

    private void checkOptions() throws HiveException {
        if (_eta <= 0.d || _eta > 1.d) {
            throw new HiveException("Invalid shrinkage: " + _eta);
        }
        if (_subsample <= 0.d || _subsample > 1.d) {
            throw new HiveException("Invalid sampling fraction: " + _subsample);
        }
        if (_minSamplesSplit <= 0) {
            throw new HiveException("Invalid minSamplesSplit: " + _minSamplesSplit);
        }
        if (_maxDepth < 1) {
            throw new HiveException("Invalid maxDepth: " + _maxDepth);
        }
    }

    /**
     * @param x features
     * @param y label
     */
    private void train(@Nonnull Matrix x, @Nonnull final int[] y) throws HiveException {
        final int numRows = x.numRows();
        if (numRows != y.length) {
            throw new HiveException(String.format("The sizes of X and Y don't match: %d != %d",
                numRows, y.length));
        }
        checkOptions();
        this._attributes = SmileExtUtils.attributeTypes(_attributes, x);

        // Shuffle training samples    
        x = SmileExtUtils.shuffle(x, y, _seed);

        final int k = smile.math.Math.max(y) + 1;
        if (k < 2) {
            throw new UDFArgumentException("Only one class or negative class labels.");
        }
        if (k == 2) {
            final int[] y2 = new int[numRows];
            for (int i = 0; i < numRows; i++) {
                if (y[i] == 1) {
                    y2[i] = 1;
                } else {
                    y2[i] = -1;
                }
            }
            train2(x, y2);
        } else {
            traink(x, y, k);
        }
    }

    private void train2(@Nonnull final Matrix x, @Nonnull final int[] y) throws HiveException {
        final int numVars = SmileExtUtils.computeNumInputVars(_numVars, x);
        if (logger.isInfoEnabled()) {
            logger.info("k: " + 2 + ", numTrees: " + _numTrees + ", shirinkage: " + _eta
                    + ", subsample: " + _subsample + ", numVars: " + numVars + ", maxDepth: "
                    + _maxDepth + ", minSamplesSplit: " + _minSamplesSplit + ", maxLeafs: "
                    + _maxLeafNodes + ", seed: " + _seed);
        }

        final int numInstances = x.numRows();
        final int numSamples = (int) Math.round(numInstances * _subsample);

        final double[] h = new double[numInstances]; // current F(x_i)
        final double[] response = new double[numInstances]; // response variable for regression tree.

        final double mu = smile.math.Math.mean(y);
        final double intercept = 0.5d * Math.log((1.d + mu) / (1.d - mu));

        for (int i = 0; i < numInstances; i++) {
            h[i] = intercept;
        }

        final ColumnMajorIntMatrix order = SmileExtUtils.sort(_attributes, x);
        final RegressionTree.NodeOutput output = new L2NodeOutput(response);

        final BitSet sampled = new BitSet(numInstances);
        final int[] bag = new int[numSamples];
        final int[] perm = new int[numSamples];
        for (int i = 0; i < numSamples; i++) {
            perm[i] = i;
        }

        long s = (this._seed == -1L) ? SmileExtUtils.generateSeed()
                : RandomNumberGeneratorFactory.createPRNG(_seed).nextLong();
        final PRNG rnd1 = RandomNumberGeneratorFactory.createPRNG(s);
        final PRNG rnd2 = RandomNumberGeneratorFactory.createPRNG(rnd1.nextLong());

        final Vector xProbe = x.rowVector();
        for (int m = 0; m < _numTrees; m++) {
            reportProgress(_progressReporter);

            SmileExtUtils.shuffle(perm, rnd1);
            for (int i = 0; i < numSamples; i++) {
                int index = perm[i];
                bag[i] = index;
                sampled.set(index);
            }

            for (int i = 0; i < numInstances; i++) {
                response[i] = 2.0d * y[i] / (1.d + Math.exp(2.d * y[i] * h[i]));
            }

            RegressionTree tree = new RegressionTree(_attributes, x, response, numVars, _maxDepth,
                _maxLeafNodes, _minSamplesSplit, _minSamplesLeaf, order, bag, output, rnd2);

            for (int i = 0; i < numInstances; i++) {
                x.getRow(i, xProbe);
                h[i] += _eta * tree.predict(xProbe);
            }

            // out-of-bag error estimate
            int oobTests = 0, oobErrors = 0;
            for (int i = sampled.nextClearBit(0); i < numInstances; i = sampled.nextClearBit(i + 1)) {
                oobTests++;
                final int pred = (h[i] > 0.d) ? 1 : 0;
                if (pred != y[i]) {
                    oobErrors++;
                }
            }
            float oobErrorRate = 0.f;
            if (oobTests > 0) {
                oobErrorRate = ((float) oobErrors) / oobTests;
            }

            forward(m + 1, intercept, _eta, oobErrorRate, tree);
            sampled.clear();
        }
    }

    /**
     * Train L-k tree boost.
     */
    private void traink(final Matrix x, final int[] y, final int k) throws HiveException {
        final int numVars = SmileExtUtils.computeNumInputVars(_numVars, x);
        if (logger.isInfoEnabled()) {
            logger.info("k: " + k + ", numTrees: " + _numTrees + ", shirinkage: " + _eta
                    + ", subsample: " + _subsample + ", numVars: " + numVars
                    + ", minSamplesSplit: " + _minSamplesSplit + ", maxDepth: " + _maxDepth
                    + ", maxLeafs: " + _maxLeafNodes + ", seed: " + _seed);
        }

        final int numInstances = x.numRows();
        final int numSamples = (int) Math.round(numInstances * _subsample);

        final double[][] h = new double[k][numInstances]; // boost tree output.
        final double[][] p = new double[k][numInstances]; // posteriori probabilities.
        final double[][] response = new double[k][numInstances]; // pseudo response.

        final ColumnMajorIntMatrix order = SmileExtUtils.sort(_attributes, x);
        final RegressionTree.NodeOutput[] output = new LKNodeOutput[k];
        for (int i = 0; i < k; i++) {
            output[i] = new LKNodeOutput(response[i], k);
        }

        final BitSet sampled = new BitSet(numInstances);
        final int[] bag = new int[numSamples];
        final int[] perm = MathUtils.permutation(numInstances);

        long s = (this._seed == -1L) ? SmileExtUtils.generateSeed()
                : RandomNumberGeneratorFactory.createPRNG(_seed).nextLong();
        final PRNG rnd1 = RandomNumberGeneratorFactory.createPRNG(s);
        final PRNG rnd2 = RandomNumberGeneratorFactory.createPRNG(rnd1.nextLong());

        // out-of-bag prediction
        final int[] prediction = new int[numInstances];
        final Vector xProbe = x.rowVector();
        for (int m = 0; m < _numTrees; m++) {
            for (int i = 0; i < numInstances; i++) {
                double max = Double.NEGATIVE_INFINITY;
                for (int j = 0; j < k; j++) {
                    final double h_ji = h[j][i];
                    if (max < h_ji) {
                        max = h_ji;
                    }
                }
                double Z = 0.0d;
                for (int j = 0; j < k; j++) {
                    double p_ji = Math.exp(h[j][i] - max);
                    p[j][i] = p_ji;
                    Z += p_ji;
                }
                for (int j = 0; j < k; j++) {
                    p[j][i] /= Z;
                }
            }

            final RegressionTree[] trees = new RegressionTree[k];

            Arrays.fill(prediction, -1);
            double max_h = Double.NEGATIVE_INFINITY;
            int oobTests = 0, oobErrors = 0;

            for (int j = 0; j < k; j++) {
                reportProgress(_progressReporter);

                final double[] response_j = response[j];
                final double[] p_j = p[j];
                final double[] h_j = h[j];

                for (int i = 0; i < numInstances; i++) {
                    if (y[i] == j) {
                        response_j[i] = 1.0d;
                    } else {
                        response_j[i] = 0.0d;
                    }
                    response_j[i] -= p_j[i];
                }

                SmileExtUtils.shuffle(perm, rnd1);
                for (int i = 0; i < numSamples; i++) {
                    int index = perm[i];
                    bag[i] = index;
                    sampled.set(i);
                }

                RegressionTree tree = new RegressionTree(_attributes, x, response[j], numVars,
                    _maxDepth, _maxLeafNodes, _minSamplesSplit, _minSamplesLeaf, order, bag,
                    output[j], rnd2);
                trees[j] = tree;

                for (int i = 0; i < numInstances; i++) {
                    x.getRow(i, xProbe);
                    double h_ji = h_j[i] + _eta * tree.predict(xProbe);
                    h_j[i] += h_ji;
                    if (h_ji > max_h) {
                        max_h = h_ji;
                        prediction[i] = j;
                    }
                }

            }// for each k

            // out-of-bag error estimate
            for (int i = sampled.nextClearBit(0); i < numInstances; i = sampled.nextClearBit(i + 1)) {
                oobTests++;
                if (prediction[i] != y[i]) {
                    oobErrors++;
                }
            }
            sampled.clear();
            float oobErrorRate = 0.f;
            if (oobTests > 0) {
                oobErrorRate = ((float) oobErrors) / oobTests;
            }

            // forward a row
            forward(m + 1, 0.d, _eta, oobErrorRate, trees);

        }// for each m
    }

    /**
     * @param m m-th boosting iteration
     */
    private void forward(final int m, final double intercept, final double shrinkage,
            final float oobErrorRate, @Nonnull final RegressionTree... trees) throws HiveException {
        Text[] models = getModel(trees);

        double[] importance = new double[_attributes.length];
        for (RegressionTree tree : trees) {
            double[] imp = tree.importance();
            for (int i = 0; i < imp.length; i++) {
                importance[i] += imp[i];
            }
        }

        Object[] forwardObjs = new Object[6];
        forwardObjs[0] = new IntWritable(m);
        forwardObjs[1] = models;
        forwardObjs[2] = new DoubleWritable(intercept);
        forwardObjs[3] = new DoubleWritable(shrinkage);
        forwardObjs[4] = WritableUtils.toWritableList(importance);
        forwardObjs[5] = new FloatWritable(oobErrorRate);

        forward(forwardObjs);

        reportProgress(_progressReporter);
        incrCounter(_iterationCounter, 1);

        logger.info("Forwarded the output of " + m + "-th Boosting iteration out of " + _numTrees);
    }

    @Nonnull
    private static Text[] getModel(@Nonnull final RegressionTree[] trees) throws HiveException {
        final int m = trees.length;
        final Text[] models = new Text[m];
        for (int i = 0; i < m; i++) {
            byte[] b = trees[i].predictSerCodegen(true);
            b = Base91.encode(b);
            models[i] = new Text(b);
        }
        return models;
    }

    /**
     * Class to calculate node output for two-class logistic regression.
     */
    private static final class L2NodeOutput implements RegressionTree.NodeOutput {

        /**
         * Pseudo response to fit.
         */
        final double[] y;

        /**
         * Constructor.
         * 
         * @param y pseudo response to fit.
         */
        public L2NodeOutput(double[] y) {
            this.y = y;
        }

        @Override
        public double calculate(int[] samples) {
            double nu = 0.0d;
            double de = 0.0d;
            for (int i = 0; i < samples.length; i++) {
                if (samples[i] > 0) {
                    double y_i = y[i];
                    double abs = Math.abs(y_i);
                    nu += y_i;
                    de += abs * (2.0d - abs);
                }
            }

            return nu / de;
        }
    }

    /**
     * Class to calculate node output for multi-class logistic regression.
     */
    private static final class LKNodeOutput implements RegressionTree.NodeOutput {

        /**
         * Responses to fit.
         */
        final double[] y;

        /**
         * The number of classes.
         */
        final double k;

        /**
         * Constructor.
         * 
         * @param response response to fit.
         */
        public LKNodeOutput(double[] response, int k) {
            this.y = response;
            this.k = k;
        }

        @Override
        public double calculate(int[] samples) {
            int n = 0;
            double nu = 0.0d;
            double de = 0.0d;
            for (int i = 0; i < samples.length; i++) {
                if (samples[i] > 0) {
                    n++;
                    double y_i = y[i];
                    double abs = Math.abs(y_i);
                    nu += y_i;
                    de += abs * (1.0d - abs);
                }
            }

            if (de < 1E-10d) {
                return nu / n;
            }
            return ((k - 1.0d) / k) * (nu / de);
        }
    }

}
