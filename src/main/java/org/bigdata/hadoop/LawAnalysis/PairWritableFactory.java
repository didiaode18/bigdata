package org.bigdata.hadoop.LawAnalysis;

import org.apache.hadoop.io.Text;

/**
 * Created by 健勤 on 2017/5/22.
 */
public class PairWritableFactory {
    public static TextPairWritable textPair(String first, String second){
        return new TextPairWritable(new Text(first), new Text(second));
    }
}
