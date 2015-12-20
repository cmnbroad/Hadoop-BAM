package org.seqdoop.hadoop_bam;

import hbparquet.hadoop.util.ContextUtil;
import htsjdk.samtools.CRAMIterator;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.cram.ref.ReferenceSource;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.samtools.reference.ReferenceSequenceFileFactory;
import htsjdk.samtools.seekablestream.SeekableStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.seqdoop.hadoop_bam.util.WrapSeekable;

public class CRAMRecordReader extends RecordReader<LongWritable, SAMRecordWritable> {

  private final LongWritable key = new LongWritable();
  private final SAMRecordWritable record = new SAMRecordWritable();
  private boolean isInitialized = false;
  private SeekableStream seekableStream;
  private long start;
  private long length;
  private CRAMIterator cramIterator;

  @Override
  public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
    if(isInitialized) {
      close();
    }
    isInitialized = true;

    final Configuration conf = ContextUtil.getConfiguration(context);
    final FileSplit fileSplit = (FileSplit) split;
    final Path file  = fileSplit.getPath();

    final String refSourcePath = conf.get(CRAMInputFormat.REFERENCE_SOURCE_PATH_PROPERTY);
    ReferenceSource refSource = null;
    try {
      refSource = refSourcePath == null ?
              new ReferenceSource() :
              new ReferenceSource(ReferenceSequenceFileFactory.getReferenceSequenceFile((Paths.get(new URI(refSourcePath)))));
    } catch (URISyntaxException e){
      throw new RuntimeException(e);
    }

    //new ReferenceSource(new File(refSourcePath)); // TODO: support HDFS

    seekableStream = WrapSeekable.openPath(conf, file);
    start = fileSplit.getStart();
    length = fileSplit.getLength();
    long end = start + length;
    // CRAMIterator right shifts boundaries by 16 so we do the reverse here
    // also subtract one from end since CRAMIterator's boundaries are inclusive
    long[] boundaries = new long[] {start << 16, (end - 1) << 16};
    cramIterator = new CRAMIterator(seekableStream, refSource, boundaries);
  }

  @Override
  public boolean nextKeyValue() {
    if (!cramIterator.hasNext()) {
      return false;
    }
    SAMRecord r = cramIterator.next();
    key.set(BAMRecordReader.getKey(r));
    record.set(r);
    return true;
  }

  @Override
  public LongWritable getCurrentKey() {
    return key;
  }

  @Override
  public SAMRecordWritable getCurrentValue() {
    return record;
  }

  @Override
  public float getProgress() throws IOException {
    return (float)(seekableStream.position() - start) / length;
  }

  @Override
  public void close() {
    cramIterator.close();
  }
}
