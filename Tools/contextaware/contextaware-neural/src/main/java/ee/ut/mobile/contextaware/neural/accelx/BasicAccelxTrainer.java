package ee.ut.mobile.contextaware.neural.accelx;

import ee.ut.mobile.contextaware.h2.DAOManager;
import ee.ut.mobile.contextaware.h2.DataSourceFactory;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;
import org.encog.ConsoleStatusReportable;
import org.encog.Encog;
import org.encog.engine.network.activation.ActivationTANH;
import org.encog.mathutil.error.ErrorCalculation;
import org.encog.mathutil.error.ErrorCalculationMode;
import org.encog.ml.data.MLData;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.data.basic.BasicMLData;
import org.encog.ml.data.folded.FoldedDataSet;
import org.encog.ml.train.MLTrain;
import org.encog.neural.networks.BasicNetwork;
import org.encog.neural.networks.training.cross.CrossValidationKFold;
import org.encog.neural.networks.training.lma.LevenbergMarquardtTraining;
import org.encog.neural.networks.training.propagation.resilient.ResilientPropagation;
import org.encog.neural.pattern.FeedForwardPattern;
import org.encog.neural.prune.PruneIncremental;
import org.encog.persist.EncogDirectoryPersistence;
import org.encog.util.EngineArray;
import org.encog.util.arrayutil.NormalizeArray;
import org.encog.util.arrayutil.TemporalWindowArray;

import java.io.File;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.util.*;

import static ee.ut.mobile.contextaware.neural.util.Constant.BATTERY_ID;


public class BasicAccelxTrainer {
    public long startingTime = 0;
    public Timestamp startingTimeStamp;
    public long lastTime = 0;
    public long size = 0;
    public final static int WINDOW_SIZE = 30;

    public final static double MAX_ERROR = 0.01;
    Map<Timestamp, Double> halls;
    private double[] normalizedAccelx;
    private List<Timestamp> timing;
    private double[] closedLoopAccelx;
    private Timestamp lastTimeStamp;
    NormalizeArray norm;
    private double median;

    public void normalizeAccelxWithTimeBetween(double lo, double hi, Timestamp start, Timestamp stop) {
        halls = DAOManager.getDataWithTimeBetween(DataSourceFactory.getDataSource(), BATTERY_ID, start, stop);

        size = halls.size();

        Set<Timestamp> times = halls.keySet();

        startingTime = ((Timestamp) ((TreeMap) halls).firstKey()).getTime();
        startingTimeStamp = ((Timestamp) ((TreeMap) halls).firstKey());
        lastTime = ((Timestamp) ((TreeMap) halls).lastKey()).getTime();
        lastTimeStamp = (Timestamp) ((TreeMap) halls).lastKey();


        DescriptiveStatistics destats = new DescriptiveStatistics();

        for (Timestamp t : times) {
            destats.addValue(halls.get(t));
        }

        median = destats.getPercentile(50);

        List<Double> cleanData = addMissingData();
        Double[] tempArray = new Double[cleanData.size()];
        cleanData.toArray(tempArray);

        double[] tempPrimitiveArray = ArrayUtils.toPrimitive(tempArray);

        norm = new NormalizeArray();
        norm.setNormalizedHigh(hi);
        norm.setNormalizedLow(lo);

        // create arrays to hold the normalized temperature
        normalizedAccelx = norm.process(tempPrimitiveArray);
        closedLoopAccelx = EngineArray.arrayCopy(normalizedAccelx);

    }


    public List<Double> addMissingData() {
        List<Double> accelxz = new ArrayList<Double>();
        timing = new ArrayList<Timestamp>();
        Timestamp newTimeStamp = startingTimeStamp;

        while (newTimeStamp.before(lastTimeStamp)) {
            accelxz.add(halls.get(newTimeStamp) != null ? halls.get(newTimeStamp) : median);
            timing.add(newTimeStamp);

            //add one second
            int sec = 1000;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(newTimeStamp.getTime());
            cal.add(Calendar.MILLISECOND, sec);
            newTimeStamp = new Timestamp(cal.getTime().getTime());
        }
        return accelxz;
    }

    public MLDataSet generateTraining() {
        TemporalWindowArray temp = new TemporalWindowArray(WINDOW_SIZE, 1);
        temp.analyze(this.normalizedAccelx);
        return temp.process(this.normalizedAccelx);
    }


    public void trainLMA(BasicNetwork network, MLDataSet training) {

       final MLTrain train = new LevenbergMarquardtTraining(network, training);


        int epoch = 1;

        do {
            train.iteration();
            System.out.println("Epoch #" + epoch + " Error:" + train.getError());
            epoch++;
        } while (train.getError() > MAX_ERROR);

        //save network file
        long time = System.currentTimeMillis();
        File networkFile = new File("NNLMA"  + getClass().getSimpleName().replace("er","ed") + time);
        System.out.println("***********************************************");
        System.out.println("NN" + getClass().getSimpleName().replace("er","ed") + time);
        System.out.println("***********************************************");
        EncogDirectoryPersistence.saveObject(networkFile, network);
    }


    public void train(BasicNetwork network, MLDataSet training) {
        final FoldedDataSet folded = new FoldedDataSet(training);
        final MLTrain train = new ResilientPropagation(network, folded);
        final CrossValidationKFold trainFolded = new CrossValidationKFold(train, 4);

        int epoch = 1;

        do {
            trainFolded.iteration();
            System.out.println("Epoch #" + epoch + " Error:" + trainFolded.getError());
            epoch++;
        } while (trainFolded.getError() > MAX_ERROR);

        
        long time = System.currentTimeMillis();
        File networkFile = new File("NNVALIDATE"  + getClass().getSimpleName().replace("er","ed") + time);
        System.out.println("***********************************************");
        System.out.println("NNVALIDATE" + getClass().getSimpleName().replace("er","ed") + time);
        System.out.println("***********************************************");
        EncogDirectoryPersistence.saveObject(networkFile, network);
    }



    public BasicNetwork pruneNetwork(MLDataSet training) {
        FeedForwardPattern pattern = new FeedForwardPattern();
        pattern.setInputNeurons(training.getInputSize());
        pattern.setOutputNeurons(training.getIdealSize());
        pattern.setActivationFunction(new ActivationTANH());

        PruneIncremental prune = new PruneIncremental(training, pattern, 100, 1, 10, new ConsoleStatusReportable());

        prune.addHiddenLayer(5, 47);
        prune.addHiddenLayer(0, 10);

        prune.process();
        return prune.getBestNetwork();
    }


    public void predict(BasicNetwork network) {
        NumberFormat f = NumberFormat.getNumberInstance();
        f.setMaximumFractionDigits(4);
        f.setMinimumFractionDigits(4);
        ErrorCalculation ec = new ErrorCalculation();
        ec.reset();

        System.out.println("Year\tActual\tPredict\tClosed Loop Predict");
        int start = new Float(timing.size() * (9.9f / 10.0f)).intValue();
        for (int evalutingpoint = start; evalutingpoint < timing.size(); evalutingpoint++) {

            // calculate based on actual data
            MLData input = new BasicMLData(WINDOW_SIZE);
            for (int i = 0; i < input.size(); i++) {
                input.setData(i, this.normalizedAccelx[(evalutingpoint - WINDOW_SIZE) + i]);
            }

            MLData output = network.compute(input);
            double prediction = output.getData(0);
            this.closedLoopAccelx[evalutingpoint] = prediction;

            // calculate "closed loop", based on predicted data
            for (int i = 0; i < input.size(); i++) {
                input.setData(i, this.closedLoopAccelx[(evalutingpoint - WINDOW_SIZE) + i]);
            }

            output = network.compute(input);
            double closedLoopPrediction = output.getData(0);

            //ErrorCalculation
            ec.updateError(prediction,normalizedAccelx[evalutingpoint]);

            // display
            System.out.println(timing.get(evalutingpoint) + "\t"
                    + f.format(norm.getStats().deNormalize(this.normalizedAccelx[evalutingpoint])) + "\t"
                    + f.format(norm.getStats().deNormalize(prediction)) + "\t"
                    + f.format(norm.getStats().deNormalize(closedLoopPrediction)));
        }

        System.out.println("Test RMSE :" + ec.calculateRMS());
    }

    public void run() {

        Calendar start = Calendar.getInstance();
        start.set(2014, Calendar.MAY, 10, 9, 12, 31);
        Calendar later = Calendar.getInstance();
        later.set(2014, Calendar.MAY, 10, 9, 56, 11);

        normalizeAccelxWithTimeBetween(0.1, 0.9, new Timestamp(start.getTime().getTime()),
                new Timestamp(later.getTime().getTime()));


        MLDataSet training = generateTraining();
        BasicNetwork network = pruneNetwork(training);
        BasicNetwork cloneForLMA = (BasicNetwork)network.clone();

        train(network, training);
        ErrorCalculation.setMode(ErrorCalculationMode.RMS);
        System.out.println("RMSE Normal: " + network.calculateError(training));
        predict(network);


        trainLMA(cloneForLMA, training);
        ErrorCalculation.setMode(ErrorCalculationMode.RMS);
        System.out.println("RMSE LMA: " + cloneForLMA.calculateError(training));
        predict(cloneForLMA);


    }

    public static void main(String[] args) {
        BasicAccelxTrainer basicAccelxTrainer = new BasicAccelxTrainer();
        basicAccelxTrainer.run();
        Encog.getInstance().shutdown();
    }
}
