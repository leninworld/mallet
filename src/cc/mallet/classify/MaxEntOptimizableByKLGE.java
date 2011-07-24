package cc.mallet.classify;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Logger;

import cc.mallet.optimize.Optimizable;
import cc.mallet.types.FeatureVector;
import cc.mallet.types.Instance;
import cc.mallet.types.InstanceList;
import cc.mallet.types.MatrixOps;
import cc.mallet.util.MalletProgressMessageLogger;
import cc.mallet.util.Maths;

/**
 * Training of MaxEnt models with labeled features using
 * Generalized Expectation Criteria.
 * 
 * Based on: 
 * "Learning from Labeled Features using Generalized Expectation Criteria"
 * Gregory Druck, Gideon Mann, Andrew McCallum
 * SIGIR 2008
 * 
 * @author Gregory Druck <a href="mailto:gdruck@cs.umass.edu">gdruck@cs.umass.edu</a>
 */

/**
 * @author gdruck
 *
 */
public class MaxEntOptimizableByKLGE extends MaxEntOptimizableByGE {
  
  private static Logger progressLogger = MalletProgressMessageLogger.getLogger(MaxEntOptimizableByKLGE.class.getName()+"-pl");
  
  /**
   * @param trainingList List with unlabeled training instances.
   * @param constraints Feature expectation constraints.
   * @param initClassifier Initial classifier.
   */
  public MaxEntOptimizableByKLGE(InstanceList trainingList, HashMap<Integer,double[]> constraints, MaxEnt initClassifier) {
    super(trainingList,constraints,initClassifier);
  } 

  public double getValue() {   
    if (!cacheStale) {
      return cachedValue;
    }
    
    if (objWeight == 0) {
      return 0.0;
    }
    
    Arrays.fill(cachedGradient,0);

    int numRefDist = constraints.size();
    int numFeatures = trainingList.getDataAlphabet().size() + 1;
    int numLabels = trainingList.getTargetAlphabet().size();
    double scalingFactor = objWeight;      
    
    if (mapping == null) {
      // mapping maps between feature indices to 
      // constraint indices
      setMapping();
    }
    
    double[][] modelExpectations = new double[numRefDist][numLabels];
    double[][] ratio = new double[numRefDist][numLabels];
    double[] featureCounts = new double[numRefDist];

    double[][] scores = new double[trainingList.size()][numLabels];
    
    double[] constraintValue = new double[numLabels];
    
    // pass 1: calculate model distribution
    for (int ii = 0; ii < trainingList.size(); ii++) {
      Instance instance = trainingList.get(ii);
      double instanceWeight = trainingList.getInstanceWeight(instance);
      
      // skip if labeled
      if (instance.getTarget() != null) {
        continue;
      }
      
      FeatureVector fv = (FeatureVector) instance.getData();
      classifier.getClassificationScoresWithTemperature(instance, temperature, scores[ii]);
      
      for (int loc = 0; loc < fv.numLocations(); loc++) {
        int featureIndex = fv.indexAtLocation(loc);
        if (constraints.containsKey(featureIndex)) {
          int cIndex = mapping.get(featureIndex);            
          double val;
          if (!useValues) {
            val = 1.;
          }
          else {
            val = fv.valueAtLocation(loc);
          }
          featureCounts[cIndex] += val;
          for (int l = 0; l < numLabels; l++) {
            modelExpectations[cIndex][l] += scores[ii][l] * val * instanceWeight;
          }
        }
      }
      
      // special case of label regularization
      if (constraints.containsKey(defaultFeatureIndex)) {
        int cIndex = mapping.get(defaultFeatureIndex); 
        featureCounts[cIndex] += 1;
        for (int l = 0; l < numLabels; l++) {
          modelExpectations[cIndex][l] += scores[ii][l] * instanceWeight;
        }        
      }
    }
    
    double value = 0;
    for (int featureIndex : constraints.keySet()) {
      int cIndex = mapping.get(featureIndex);
      if (featureCounts[cIndex] > 0) {
        for (int label = 0; label < numLabels; label++) {
          double cProb = constraints.get(featureIndex)[label];
          // normalize by count
          modelExpectations[cIndex][label] /= featureCounts[cIndex];
          ratio[cIndex][label] =  cProb / modelExpectations[cIndex][label];
          // add to the cross entropy term
          value += scalingFactor * cProb * Math.log(modelExpectations[cIndex][label]);
          // add to the entropy term
          if (cProb > 0) {
            value -= scalingFactor * cProb * Math.log(cProb);
          }
        }
        assert(Maths.almostEquals(MatrixOps.sum(modelExpectations[cIndex]),1));
      }
    }

    // pass 2: determine per example gradient
    for (int ii = 0; ii < trainingList.size(); ii++) {
      Instance instance = trainingList.get(ii);
      
      // skip if labeled
      if (instance.getTarget() != null) {
        continue;
      }
      
      Arrays.fill(constraintValue,0);
      double instanceExpectation = 0;
      double instanceWeight = trainingList.getInstanceWeight(instance);
      FeatureVector fv = (FeatureVector) instance.getData();

      for (int loc = 0; loc < fv.numLocations() + 1; loc++) {
        int featureIndex;
        if (loc == fv.numLocations()) {
          featureIndex = defaultFeatureIndex;
        }
        else {
          featureIndex = fv.indexAtLocation(loc);
        }
        
        if (constraints.containsKey(featureIndex)) {
          int cIndex = mapping.get(featureIndex);

          // skip if this feature never occurred
          if (featureCounts[cIndex] == 0) {
            continue;
          }

          double val;
          if ((featureIndex == defaultFeatureIndex)||(!useValues)) {
            val = 1;
          }
          else {
            val = fv.valueAtLocation(loc);
          }
          
          for (int label = 0; label < numLabels; label++) {
            constraintValue[label] += (val / featureCounts[cIndex]) * ratio[cIndex][label];
          }
          
          // compute \sum_y p(y|x) \hat{g}_y / \bar{g}_y
          for (int label = 0; label < numLabels; label++) {
            instanceExpectation += (val / featureCounts[cIndex]) * ratio[cIndex][label] * scores[ii][label];
          }
        }
      }

      // define C = \sum_y p(y|x) g_y(y,x) \hat{g}_y / \bar{g}_y
      // compute \sum_y  p(y|x) g_y(x,y) f(x,y) * (\hat{g}_y / \bar{g}_y - C)
      for (int label = 0; label < numLabels; label++) {
        if (scores[ii][label] == 0)
          continue;
        assert (!Double.isInfinite(scores[ii][label]));
        double weight = scalingFactor * instanceWeight * temperature * scores[ii][label] * (constraintValue[label] - instanceExpectation);

        MatrixOps.rowPlusEquals(cachedGradient, numFeatures, label, fv, weight);
        cachedGradient[numFeatures * label + defaultFeatureIndex] += weight;
      }  
    }

    cachedValue = value;
    cacheStale = false;
    
    double reg = getRegularization();
    progressLogger.info ("Value (GE=" + value + " Gaussian prior= " + reg + ") = " + cachedValue);
    
    return value;
  }
}