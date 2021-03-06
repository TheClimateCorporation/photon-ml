/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.normalization

import scala.util.Random

import breeze.linalg.{DenseVector, SparseVector, Vector}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.{ModelTraining, TaskType}
import com.linkedin.photon.ml.TaskType.TaskType
import com.linkedin.photon.ml.data.LabeledPoint
import com.linkedin.photon.ml.function.glm.{DistributedGLMLossFunction, LogisticLossFunction, PoissonLossFunction, SquaredLossFunction}
import com.linkedin.photon.ml.model.Coefficients
import com.linkedin.photon.ml.normalization.NormalizationType.NormalizationType
import com.linkedin.photon.ml.optimization.OptimizerType.OptimizerType
import com.linkedin.photon.ml.optimization._
import com.linkedin.photon.ml.optimization.game.FixedEffectOptimizationConfiguration
import com.linkedin.photon.ml.stat.BasicStatisticalSummary
import com.linkedin.photon.ml.supervised.classification.{BinaryClassifier, LogisticRegressionModel}
import com.linkedin.photon.ml.test.Assertions.assertIterableEqualsWithTolerance
import com.linkedin.photon.ml.test.{CommonTestUtils, SparkTestUtils}
import com.linkedin.photon.ml.util.GameTestUtils

/**
 * Integration tests for [[NormalizationContext]].
 */
class NormalizationContextIntegTest extends SparkTestUtils with GameTestUtils {

  import NormalizationContextIntegTest._

  /**
   * Generate sample data for binary classification problems according to a random seed and a model.
   * The labels in the data is exactly predicted by the input model.
   *
   * @param sc The [[SparkContext]] for the test
   * @param seed The random seed used to generate the data
   * @param model The input model used to generate the labels in the data
   * @return The data RDD
   */
  private def generateSampleRDD(sc: SparkContext, seed: Int, model: LogisticRegressionModel): RDD[LabeledPoint] = {
    val data = drawBalancedSampleFromNumericallyBenignDenseFeaturesForBinaryClassifierLocal(seed, SIZE, DIMENSION)
      .map { case (_, sparseVector: SparseVector[Double]) =>
        // Append data with the intercept
        val size = sparseVector.size
        val vector = new SparseVector[Double](
          sparseVector.index :+ size,
          sparseVector.data :+ 1.0,
          size + 1
        )

        // Replace the random label using the one predicted by the input model
        val label = model.predictClass(vector, THRESHOLD)

        new LabeledPoint(label, vector)
      }
      .toArray

    sc.parallelize(data)
  }

  /**
   * Check the correctness of training with a specific normalization type. This check involves unregularized training.
   * The prediction of an unregularized trained model should predict exactly the same label as the one in the input
   * training data, given that the labels are generated by a model without noise. This method also checks the precision
   * of a test data set.
   *
   * @param trainRDD Training data set
   * @param testRDD Test data set
   * @param normalizationType Normalization type
   */
  private def checkTrainingOfNormalizationType(
    trainRDD: RDD[LabeledPoint],
    testRDD: RDD[LabeledPoint],
    normalizationType: NormalizationType): Unit = {

    // This is necessary to make Spark not complain serialization error of this class.
    val summary = BasicStatisticalSummary(trainRDD)
    val normalizationContext = NormalizationContext(normalizationType, summary, Some(DIMENSION))
    val threshold = THRESHOLD
    val (models, _) = ModelTraining.trainGeneralizedLinearModel(
      trainRDD,
      TaskType.LOGISTIC_REGRESSION,
      OptimizerType.LBFGS,
      L2RegularizationContext,
      regularizationWeights = List(0.0),
      normalizationContext,
      NUM_ITER,
      CONVERGENCE_TOLERANCE,
      enableOptimizationStateTracker = true,
      constraintMap = None,
      treeAggregateDepth = 1,
      useWarmStart = false)

    assertEquals(models.size, 1)

    val model = models.head._2.asInstanceOf[BinaryClassifier]

    // For all types of normalization, the unregularized trained model should predictClass the same label.
    trainRDD.foreach { case LabeledPoint(label, vector, _, _) =>
      val prediction = model.predictClass(vector, threshold)
      assertEquals(prediction, label)
    }

    // For a test data set, the trained model should recover a certain level of precision.
    val correct = testRDD
      .filter { case LabeledPoint(label, vector, _, _) =>
        val prediction = model.predictClass(vector, threshold)
        label == prediction
      }
      .count

    assertTrue(correct.toDouble / SIZE >= PRECISION, s"Precision check [$correct/$SIZE >= $PRECISION] failed.")
  }

  /**
   * Tests that the results of training with each type of normalization result in the same prediction. This class is
   * mostly a driver for [[checkTrainingOfNormalizationType]] which is the real workhorse.
   */
  @Test
  def testNormalization(): Unit = sparkTest("testNormalization") {

    Random.setSeed(SEED)

    // The size of the vector is _dimension + 1 due to the intercept
    val coef = (for (_ <- 0 to DIMENSION) yield Random.nextGaussian()).toArray
    val model = new LogisticRegressionModel(Coefficients(DenseVector(coef)))
    val trainRDD = generateSampleRDD(sc, SEED, model)
    val testRDD = generateSampleRDD(sc, SEED + 1, model)

    NormalizationType.values.foreach(checkTrainingOfNormalizationType(trainRDD, testRDD, _))
  }

  @DataProvider(name = "generateStandardizationTestData")
  def generateStandardizationTestData(): Array[Array[Any]] = {
    (for (x <- OptimizerType.values;
          y <- TaskType.values.filter { t => t != TaskType.SMOOTHED_HINGE_LOSS_LINEAR_SVM && t != TaskType.NONE })
      yield Array[Any](x, y)).toArray
  }

  /**
   * This is a sophisticated test for standardization with the heart data set. An objective function with
   * normal input and a loss function with standardization context should produce the same result as an objective
   * function with standardized input and a plain loss function. The heart data set seems to be well-behaved, so the
   * final objective and the model coefficients can be reproduced even after many iterations.
   *
   * @param optimizerType Optimizer type
   * @param taskType Task type
   */
  @Test(dataProvider = "generateStandardizationTestData")
  def testOptimizationWithStandardization(
      optimizerType: OptimizerType,
      taskType: TaskType): Unit = sparkTest("testObjectivesAfterNormalization") {

    // Read heart data
    val heartDataRDD: RDD[LabeledPoint] = {
      val tt = getClass.getClassLoader.getResource("DriverIntegTest/input/heart.txt")
      val inputFile = tt.toString
      val rawInput = sc.textFile(inputFile, 1)
      val trainRDD: RDD[LabeledPoint] = rawInput.map(x => {
        val y = x.split(" ")
        val label = y(0).toDouble / 2 + 0.5
        val features = y.drop(1).map(z => z.split(":")(1).toDouble) :+ 1.0
        new LabeledPoint(label, DenseVector(features))
      }).persist()
      trainRDD
    }

    // Build normalization context
    val normalizationContext: NormalizationContext = {
      val dim = heartDataRDD.take(1)(0).features.size
      lazy val summary = BasicStatisticalSummary(heartDataRDD)
      NormalizationContext(NormalizationType.STANDARDIZATION, summary, Some(dim - 1))
    }

    val normalizationBroadcast = sc.broadcast(normalizationContext)
    val noNormalizationBroadcast = sc.broadcast(NoNormalization())

    // Build the transformed rdd for validation
    val transformedRDD: RDD[LabeledPoint] = {
      val transformedRDD = heartDataRDD.map {
        case LabeledPoint(label, features, weight, offset) =>
          val transformedFeatures = transformVector(normalizationContext, features)
          new LabeledPoint(label, transformedFeatures, weight, offset)
      }.persist()

      // Verify that the transformed rdd will have the correct transformation condition
      val summaryAfterStandardization = BasicStatisticalSummary(transformedRDD)
      val dim = summaryAfterStandardization.mean.size

      summaryAfterStandardization
        .mean
        .toArray
        .dropRight(1)
        .foreach(x => assertEquals(0.0, x, CommonTestUtils.HIGH_PRECISION_TOLERANCE))
      summaryAfterStandardization
        .variance
        .toArray
        .dropRight(1)
        .foreach(x => assertEquals(1.0, x, CommonTestUtils.HIGH_PRECISION_TOLERANCE))
      assertEquals(1.0, summaryAfterStandardization.mean(dim - 1), CommonTestUtils.HIGH_PRECISION_TOLERANCE)
      assertEquals(0.0, summaryAfterStandardization.variance(dim - 1), CommonTestUtils.HIGH_PRECISION_TOLERANCE)

      transformedRDD
    }

    val configuration = FixedEffectOptimizationConfiguration(generateOptimizerConfig())
    val objectiveFunction = taskType match {
      case TaskType.LOGISTIC_REGRESSION =>
        DistributedGLMLossFunction(sc, configuration, treeAggregateDepth = 1)(LogisticLossFunction)

      case TaskType.LINEAR_REGRESSION =>
        DistributedGLMLossFunction(sc, configuration, treeAggregateDepth = 1)(SquaredLossFunction)

      case TaskType.POISSON_REGRESSION =>
        DistributedGLMLossFunction(sc, configuration, treeAggregateDepth = 1)(PoissonLossFunction)
    }
    val optimizerNorm = optimizerType match {
      case OptimizerType.LBFGS =>
        new LBFGS(tolerance = 1.0E-6, maxNumIterations = 100, normalizationContext = normalizationBroadcast)

      case OptimizerType.TRON =>
        new TRON(tolerance = 1.0E-6, maxNumIterations = 100, normalizationContext = normalizationBroadcast)
    }
    val optimizerNoNorm = optimizerType match {
      case OptimizerType.LBFGS =>
        new LBFGS(tolerance = 1.0E-6, maxNumIterations = 100, normalizationContext = noNormalizationBroadcast)

      case OptimizerType.TRON =>
        new TRON(tolerance = 1.0E-6, maxNumIterations = 100, normalizationContext = noNormalizationBroadcast)
    }

    // Train the original data with a loss function binding normalization
    val zero = Vector.zeros[Double](objectiveFunction.domainDimension(heartDataRDD))
    val (model1, objective1) = optimizerNorm.optimize(objectiveFunction, zero)(heartDataRDD)
    // Train the transformed data with a normal loss function
    val (model2, objective2) = optimizerNoNorm.optimize(objectiveFunction, zero)(transformedRDD)

    normalizationBroadcast.unpersist()
    noNormalizationBroadcast.unpersist()
    heartDataRDD.unpersist()
    transformedRDD.unpersist()

    // The two objective function/optimization should be exactly the same up to numerical accuracy.
    assertEquals(objective1, objective2, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertIterableEqualsWithTolerance(model1.toArray, model2.toArray, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
  }
}

object NormalizationContextIntegTest {

  private val SEED = 1
  private val SIZE = 100
  private val DIMENSION = 10
  private val THRESHOLD = 0.5
  private val CONVERGENCE_TOLERANCE = 1E-5
  private val NUM_ITER = 100
  private val PRECISION = 0.95

  /**
   * For testing purpose only. This is not designed to be efficient. This method transforms a vector from the original
   * space to a normalized space.
   *
   * @param input Input vector
   * @return Transformed vector
   */
  def transformVector(normalizationContext: NormalizationContext, input: Vector[Double]): Vector[Double] = {
    (normalizationContext.factors, normalizationContext.shifts) match {
      case (Some(fs), Some(ss)) =>
        require(fs.size == input.size, "Vector size and the scaling factor size are different.")
        (input - ss) :* fs
      case (Some(fs), None) =>
        require(fs.size == input.size, "Vector size and the scaling factor size are different.")
        input :* fs
      case (None, Some(ss)) =>
        require(ss.size == input.size, "Vector size and the scaling factor size are different.")
        input - ss
      case (None, None) =>
        input
    }
  }
}
