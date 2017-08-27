package similarity.test;

import com.spark.test.tfidf.util.SimilartyData;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.ml.feature.HashingTF;
import org.apache.spark.ml.feature.IDF;
import org.apache.spark.ml.feature.IDFModel;
import org.apache.spark.ml.feature.Tokenizer;
import org.apache.spark.ml.linalg.BLAS;
import org.apache.spark.ml.linalg.Vector;
import org.apache.spark.ml.linalg.Vectors;
import org.apache.spark.sql.*;
import org.lionsoul.jcseg.tokenizer.core.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.util.*;

/**
 * Created by 李小娟 on 2017/8/27.
 */
public class EuclideanTest {

    private static SparkSession spark = null;
    private static String splitTag = "@==@";
    public static Dataset<Row> tfidf(Dataset<Row> dataset) {
        Tokenizer tokenizer = new Tokenizer().setInputCol("segment").setOutputCol("words");
        Dataset<Row> wordsData = tokenizer.transform(dataset);
        HashingTF hashingTF = new HashingTF()
                .setInputCol("words")
                .setOutputCol("rawFeatures");
        Dataset<Row> featurizedData = hashingTF.transform(wordsData);
        IDF idf = new IDF().setInputCol("rawFeatures").setOutputCol("features");
        IDFModel idfModel = idf.fit(featurizedData);
        Dataset<Row> rescaledData = idfModel.transform(featurizedData);
        return rescaledData;
    }

    public static Dataset<Row> readTxt(String dataPath) {
        JavaRDD<TfIdfData> newsInfoRDD = spark.read().textFile(dataPath).javaRDD().map(new Function<String, TfIdfData>() {
            private ISegment seg = null;
            private void initSegment() throws Exception {
                if (seg == null) {
                    JcsegTaskConfig config = new JcsegTaskConfig();
                    config.setLoadCJKPos(true);
                    String path = new File("").getAbsolutePath() + "/data/lexicon";
                    System.out.println(new File("").getAbsolutePath());
                    ADictionary dic = DictionaryFactory.createDefaultDictionary(config);
                    dic.loadDirectory(path);
                    seg = SegmentFactory.createJcseg(JcsegTaskConfig.COMPLEX_MODE, config, dic);
                }
            }

            public TfIdfData call(String line) throws Exception {
                initSegment();
                TfIdfData newsInfo = new TfIdfData();

                String[] lines = line.split(splitTag);
                if(lines.length < 5){
                    System.out.println("error==" + lines[0] + " " + lines[1]);
                }
                String id = lines[0];
                String publish_timestamp = lines[1];
                String title = lines[2];
                String content = lines[3];
                String source = lines.length >4 ? lines[4] : "" ;

                seg.reset(new StringReader(content));
                StringBuffer sff = new StringBuffer();
                IWord word = seg.next();
                while (word != null) {
                    sff.append(word.getValue()).append(" ");
                    word = seg.next();
                }
                newsInfo.setId(id);
                newsInfo.setTitle(title);
                newsInfo.setSegment(sff.toString());
                return newsInfo;
            }
        });
        Dataset<Row> dataset = spark.createDataFrame(
                newsInfoRDD,
                TfIdfData.class
        );
        return dataset;
    }
    public static SparkSession initSpark() {
        if (spark == null) {
            spark = SparkSession
                    .builder()
                    .appName("SimilarityTest").master("local[3]")
                    .getOrCreate();
        }
        return spark;
    }
    public static void similarDataset(String id, Dataset<Row> dataSet, String datePath) throws Exception{
        Row firstRow = dataSet.select("id", "title", "features").where("id ='" + id + "'").first();
        Vector firstFeatures = firstRow.getAs(2);

        Dataset<SimilartyData> similarDataset = dataSet.select("id", "title", "features").map(new MapFunction<Row, SimilartyData>(){
            public SimilartyData call(Row row) {
                String id = row.getString(0);
                String title = row.getString(1);
                Vector features = row.getAs(2);
                double dot = BLAS.dot(firstFeatures.toSparse(), features.toSparse());
                double v1 = Vectors.norm(firstFeatures.toSparse(), 2.0);
                double v2 = Vectors.norm(features.toSparse(), 2.0);
                double similarty = dot / (v1 * v2);
                SimilartyData similartyData = new SimilartyData();
                similartyData.setId(id);
                similartyData.setTitle(title);
                similartyData.setSimilarty(similarty);
                return similartyData;
            }
        }, Encoders.bean(SimilartyData.class));
        Dataset<Row> similarDataset2 = spark.createDataFrame(
                similarDataset.toJavaRDD(),
                SimilartyData.class
        );

        FileOutputStream out = new FileOutputStream(datePath);
        OutputStreamWriter osw = new OutputStreamWriter(out, "UTF-8");
        similarDataset2.select("id", "title", "similarty").sort(functions.desc("similarty")).collectAsList().forEach(row->{
            try{
                StringBuffer sff = new StringBuffer();
                String sid = row.getAs(0);
                String title = row.getAs(1);
                double similarty = row.getAs(2);
                sff.append(sid).append(" ").append(similarty).append(" ").append(title).append("\n");
                osw.write(sff.toString());
            }
            catch(Exception e){
                e.printStackTrace();
            }
        });
        osw.close();
        out.close();
    }
    public static void run() throws Exception{
        initSpark();
        String dataPath = new File("").getAbsolutePath() + "/data/penngo_07_12.txt";

        Dataset<Row> dataSet = readTxt(dataPath);
        dataSet.show();
        Dataset<Row> tfidfDataSet = tfidf(dataSet);
        String id = "58528946cc9434e17d8b4593";
        String similarFile = new File("").getAbsolutePath() + "/data/penngo_07_12_similar.txt";
        similarDataset(id, tfidfDataSet, similarFile);

    }

    public static void main(String[] args) throws Exception{
        //window上运行
        //System.setProperty("hadoop.home.dir", "D:/penngo/hadoop-2.6.4");
        //System.setProperty("HADOOP_USER_NAME", "root");
        run();
    }
}
