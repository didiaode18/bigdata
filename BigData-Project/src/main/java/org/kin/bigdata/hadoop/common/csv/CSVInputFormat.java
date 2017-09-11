package org.kin.bigdata.hadoop.common.csv;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;

import java.io.IOException;
import java.util.List;

/**
 * Created by huangjianqin on 2017/9/4.
 * base on opencsv
 */
public class CSVInputFormat extends FileInputFormat<LongWritable, List<Text>> {
    public static final String IS_ZIPFILE = "mapreduce.csvinput.zipfile";
    public static final boolean DEFAULT_ZIP = false;

    @Override
    public RecordReader<LongWritable, List<Text>> createRecordReader(InputSplit inputSplit, TaskAttemptContext taskAttemptContext) throws IOException, InterruptedException {
        RecordReader<LongWritable, List<Text>> recordReader = new CSVRecordReader();
        recordReader.initialize(inputSplit, taskAttemptContext);
        return recordReader;
    }
}