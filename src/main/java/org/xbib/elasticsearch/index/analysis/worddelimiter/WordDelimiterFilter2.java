package org.xbib.elasticsearch.index.analysis.worddelimiter;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.RamUsageEstimator;

import java.io.IOException;
import java.util.Set;

/**
 * Splits words into subwords and performs optional transformations on subword groups.
 * Words are split into subwords with the following rules:
 * - split on intra-word delimiters (by default, all non alpha-numeric characters).
 * - "Wi-Fi" -&gt; "Wi", "Fi"
 * - split on case transitions
 * - "PowerShot" -&gt; "Power", "Shot"
 * - split on letter-number transitions
 * - "SD500" -&gt; "SD", "500"
 * - leading and trailing intra-word delimiters on each subword are ignored
 * - "//hello---there, 'dude'" -&gt; "hello", "there", "dude"
 * - trailing "'s" are removed for each subword
 * - "O'Neil's" -&gt; "O", "Neil"
 * - Note: this step isn't performed in a separate filter because of possible subword combinations.
 * The combinations parameter affects how subwords are combined:
 * - combinations="0" causes no subword combinations.
 * - "PowerShot" -&gt; 0:"Power", 1:"Shot"  (0 and 1 are the token positions)
 * - combinations="1" means that in addition to the subwords, maximum runs of non-numeric subwords are catenated and produced at the same position of the last subword in the run.
 * - "PowerShot" -&gt; 0:"Power", 1:"Shot" 1:"PowerShot"
 * - "A's+B's&amp;C's" -&gt; 0:"A", 1:"B", 2:"C", 2:"ABC"
 * - "Super-Duper-XL500-42-AutoCoder!" -&gt; 0:"Super", 1:"Duper", 2:"XL", 2:"SuperDuperXL", 3:"500" 4:"42", 5:"Auto", 6:"Coder", 6:"AutoCoder"
 * One use for WordDelimiterFilter2 is to help match words with different subword delimiters.
 * For example, if the source text contained "wi-fi" one may want "wifi" "WiFi" "wi-fi" "wi+fi" queries to all match.
 * One way of doing so is to specify combinations="1" in the analyzer used for indexing, and combinations="0" (the default)
 * in the analyzer used for querying.  Given that the current StandardTokenizer immediately removes many intra-word
 * delimiters, it is recommended that this filter be used after a tokenizer that does not do this (such as WhitespaceTokenizer).
 * The difference with WordDelimiterFilter is the new ALL_PARTS_AT_SAME_POSITION flag.
 * It permits to analyze "PowerShot" into 0:"Power", 0:"Shot", where 0: stands for the token positions.
 */

public final class WordDelimiterFilter2 extends TokenFilter implements WordDelimiterFlags {

    /**
     * If not null is the set of tokens to protect from being delimited
     */
    private final Set<String> protWords;

    private final int flags;

    private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAttribute = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncAttribute = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAttribute = addAttribute(TypeAttribute.class);

    // used for iterating word delimiter breaks
    private final WordDelimiterIterator iterator;

    // used for concatenating runs of similar typed subwords (word,number)
    private final WordDelimiterConcatenation concat = new WordDelimiterConcatenation();
    // number of subwords last output by concat.
    private int lastConcatCount = 0;

    // used for catenate all
    private final WordDelimiterConcatenation concatAll = new WordDelimiterConcatenation();

    // used for accumulating position increment gaps
    private int accumPosInc = 0;

    private char savedBuffer[] = new char[1024];
    private int savedStartOffset;
    private int savedEndOffset;
    private String savedType;
    private boolean hasSavedState = false;
    // if length by start + end offsets doesn't match the term text then assume
    // this is a synonym and don't adjust the offsets.
    private boolean hasIllegalOffsets = false;

    // for a run of the same subword type within a word, have we output anything?
    private boolean hasOutputToken = false;
    // when preserve original is on, have we output any token following it?
    // this token must have posInc=0!
    private boolean hasOutputFollowingOriginal = false;

    /**
     * Creates a new WordDelimiterFilter2
     *
     * @param in                 TokenStream to be filtered
     * @param charTypeTable      table containing character types
     * @param configurationFlags Flags configuring the filter
     * @param protWords          If not null is the set of tokens to protect from being delimited
     */
    public WordDelimiterFilter2(TokenStream in, byte[] charTypeTable, int configurationFlags, Set<String> protWords) {
        super(in);
        this.flags = configurationFlags;
        this.protWords = protWords;
        this.iterator = new WordDelimiterIterator(
                charTypeTable, has(SPLIT_ON_CASE_CHANGE), has(SPLIT_ON_NUMERICS), has(STEM_ENGLISH_POSSESSIVE));
    }

    /**
     * Creates a new WordDelimiterFilter2 using {@link org.apache.lucene.analysis.miscellaneous.WordDelimiterIterator#DEFAULT_WORD_DELIM_TABLE}
     * as its charTypeTable
     *
     * @param in                 TokenStream to be filtered
     * @param configurationFlags Flags configuring the filter
     * @param protWords          If not null is the set of tokens to protect from being delimited
     */
    public WordDelimiterFilter2(TokenStream in, int configurationFlags, Set<String> protWords) {
        this(in, WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE, configurationFlags, protWords);
    }

    @Override
    public boolean incrementToken() throws IOException {
        while (true) {
            if (!hasSavedState) {
                // process a new input word
                if (!input.incrementToken()) {
                    return false;
                }

                int termLength = termAttribute.length();
                char[] termBuffer = termAttribute.buffer();

                accumPosInc += posIncAttribute.getPositionIncrement();

                iterator.setText(termBuffer, termLength);
                iterator.next();

                // word of no delimiters, or protected word: just return it
                if ((iterator.current == 0 && iterator.end == termLength) ||
                        (protWords != null && protWords.contains(new String(termBuffer, 0, termLength)))) {
                    posIncAttribute.setPositionIncrement(accumPosInc);
                    accumPosInc = 0;
                    return true;
                }

                // word of simply delimiters
                if (iterator.end == WordDelimiterIterator.DONE && !has(PRESERVE_ORIGINAL)) {
                    // if the posInc is 1, simply ignore it in the accumulation
                    if (posIncAttribute.getPositionIncrement() == 1) {
                        accumPosInc--;
                    }
                    continue;
                }

                saveState();

                hasOutputToken = false;
                hasOutputFollowingOriginal = !has(PRESERVE_ORIGINAL);
                lastConcatCount = 0;

                if (has(PRESERVE_ORIGINAL)) {
                    posIncAttribute.setPositionIncrement(accumPosInc);
                    accumPosInc = 0;
                    return true;
                }
            }

            // at the end of the string, output any concatenations
            if (iterator.end == WordDelimiterIterator.DONE) {
                if (!concat.isEmpty()) {
                    if (flushConcatenation(concat)) {
                        return true;
                    }
                }

                if (!concatAll.isEmpty()) {
                    // only if we haven't output this same combo above!
                    if (concatAll.subwordCount > lastConcatCount) {
                        concatAll.writeAndClear();
                        return true;
                    }
                    concatAll.clear();
                }

                // no saved concatenations, on to the next input word
                hasSavedState = false;
                continue;
            }

            // word surrounded by delimiters: always output
            if (iterator.isSingleWord()) {
                generatePart(true);
                iterator.next();
                return true;
            }

            int wordType = iterator.type();

            // do we already have queued up incompatible concatenations?
            if (!concat.isEmpty() && (concat.type & wordType) == 0) {
                if (flushConcatenation(concat)) {
                    hasOutputToken = false;
                    return true;
                }
                hasOutputToken = false;
            }

            // add subwords depending upon options
            if (shouldConcatenate(wordType)) {
                if (concat.isEmpty()) {
                    concat.type = wordType;
                }
                concatenate(concat);
            }

            // add all subwords (catenateAll)
            if (has(CATENATE_ALL)) {
                concatenate(concatAll);
            }

            // if we should output the word or number part
            if (shouldGenerateParts(wordType)) {
                generatePart(false);
                iterator.next();
                return true;
            }

            iterator.next();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() throws IOException {
        super.reset();
        hasSavedState = false;
        concat.clear();
        concatAll.clear();
        accumPosInc = 0;
    }

    /**
     * Saves the existing attribute states
     */
    private void saveState() {
        // otherwise, we have delimiters, save state
        savedStartOffset = offsetAttribute.startOffset();
        savedEndOffset = offsetAttribute.endOffset();
        // if length by start + end offsets doesn't match the term text then assume this is a synonym and don't adjust the offsets.
        hasIllegalOffsets = (savedEndOffset - savedStartOffset != termAttribute.length());
        savedType = typeAttribute.type();

        if (savedBuffer.length < termAttribute.length()) {
            savedBuffer = new char[ArrayUtil.oversize(termAttribute.length(), RamUsageEstimator.NUM_BYTES_CHAR)];
        }

        System.arraycopy(termAttribute.buffer(), 0, savedBuffer, 0, termAttribute.length());
        iterator.text = savedBuffer;

        hasSavedState = true;
    }

    /**
     * Flushes the given WordDelimiterConcatenation by either writing its concat and then clearing, or just clearing.
     *
     * @param concatenation WordDelimiterConcatenation that will be flushed
     * @return {@code true} if the concatenation was written before it was cleared, {@code false} otherwise
     */
    private boolean flushConcatenation(WordDelimiterConcatenation concatenation) {
        lastConcatCount = concatenation.subwordCount;
        if (concatenation.subwordCount != 1 || !shouldGenerateParts(concatenation.type)) {
            concatenation.writeAndClear();
            return true;
        }
        concatenation.clear();
        return false;
    }

    /**
     * Determines whether to concatenate a word or number if the current word is the given type
     *
     * @param wordType Type of the current word used to determine if it should be concatenated
     * @return {@code true} if concatenation should occur, {@code false} otherwise
     */
    private boolean shouldConcatenate(int wordType) {
        return (has(CATENATE_WORDS) && isAlpha(wordType)) || (has(CATENATE_NUMBERS) && isDigit(wordType));
    }

    /**
     * Determines whether a word/number part should be generated for a word of the given type
     *
     * @param wordType Type of the word used to determine if a word/number part should be generated
     * @return {@code true} if a word/number part should be generated, {@code false} otherwise
     */
    private boolean shouldGenerateParts(int wordType) {
        return (has(GENERATE_WORD_PARTS) && isAlpha(wordType)) || (has(GENERATE_NUMBER_PARTS) && isDigit(wordType));
    }

    /**
     * Concatenates the saved buffer to the given WordDelimiterConcatenation
     *
     * @param concatenation WordDelimiterConcatenation to concatenate the buffer to
     */
    private void concatenate(WordDelimiterConcatenation concatenation) {
        if (concatenation.isEmpty()) {
            concatenation.startOffset = savedStartOffset + iterator.current;
        }
        concatenation.append(savedBuffer, iterator.current, iterator.end - iterator.current);
        concatenation.endOffset = savedStartOffset + iterator.end;
    }

    /**
     * Generates a word/number part, updating the appropriate attributes
     *
     * @param isSingleWord {@code true} if the generation is occurring from a single word, {@code false} otherwise
     */
    private void generatePart(boolean isSingleWord) {
        clearAttributes();
        termAttribute.copyBuffer(savedBuffer, iterator.current, iterator.end - iterator.current);

        int startOffset = savedStartOffset + iterator.current;
        int endOffset = savedStartOffset + iterator.end;

        if (hasIllegalOffsets) {
            // historically this filter did this regardless for 'isSingleWord',
            // but we must do a sanity check:
            if (isSingleWord && startOffset <= savedEndOffset) {
                offsetAttribute.setOffset(startOffset, savedEndOffset);
            } else {
                offsetAttribute.setOffset(savedStartOffset, savedEndOffset);
            }
        } else {
            offsetAttribute.setOffset(startOffset, endOffset);
        }
        posIncAttribute.setPositionIncrement(position(false));
        typeAttribute.setType(savedType);
    }

    /**
     * Get the position increment gap for a subword or concatenation
     *
     * @param inject true if this token wants to be injected
     * @return position increment gap
     */
    private int position(boolean inject) {
        int posInc = accumPosInc;

        if (hasOutputToken) {
            accumPosInc = 0;
            return inject ? 0 : Math.max(
                    has(ALL_PARTS_AT_SAME_POSITION) ? 0 : 1,
                    posInc);
        }

        hasOutputToken = true;

        if (!hasOutputFollowingOriginal) {
            // the first token following the original is 0 regardless
            hasOutputFollowingOriginal = true;
            return 0;
        }
        // clear the accumulated position increment
        accumPosInc = 0;
        return Math.max(has(ALL_PARTS_AT_SAME_POSITION) ? 0 : 1, posInc);
    }

    /**
     * Checks if the given word type includes {@link #ALPHA}
     *
     * @param type Word type to check
     * @return {@code true} if the type contains ALPHA, {@code false} otherwise
     */
    static boolean isAlpha(int type) {
        return (type & ALPHA) != 0;
    }

    /**
     * Checks if the given word type includes {@link #DIGIT}
     *
     * @param type Word type to check
     * @return {@code true} if the type contains DIGIT, {@code false} otherwise
     */
    static boolean isDigit(int type) {
        return (type & DIGIT) != 0;
    }

    /**
     * Checks if the given word type includes {@link #SUBWORD_DELIM}
     *
     * @param type Word type to check
     * @return {@code true} if the type contains SUBWORD_DELIM, {@code false} otherwise
     */
    static boolean isSubwordDelim(int type) {
        return (type & SUBWORD_DELIM) != 0;
    }

    /**
     * Checks if the given word type includes {@link #UPPER}
     *
     * @param type Word type to check
     * @return {@code true} if the type contains UPPER, {@code false} otherwise
     */
    static boolean isUpper(int type) {
        return (type & UPPER) != 0;
    }

    /**
     * Determines whether the given flag is set
     *
     * @param flag Flag to see if set
     * @return {@code true} if flag is set
     */
    private boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /**
     * A WDF concatenated 'run'
     */
    final class WordDelimiterConcatenation {
        final StringBuilder buffer = new StringBuilder();
        int startOffset;
        int endOffset;
        int type;
        int subwordCount;

        /**
         * Appends the given text of the given length, to the concetenation at the given offset
         *
         * @param text   Text to append
         * @param offset Offset in the concetenation to add the text
         * @param length Length of the text to append
         */
        void append(char text[], int offset, int length) {
            buffer.append(text, offset, length);
            subwordCount++;
        }

        /**
         * Writes the concatenation to the attributes
         */
        void write() {
            clearAttributes();
            if (termAttribute.length() < buffer.length()) {
                termAttribute.resizeBuffer(buffer.length());
            }
            char termbuffer[] = termAttribute.buffer();

            buffer.getChars(0, buffer.length(), termbuffer, 0);
            termAttribute.setLength(buffer.length());

            if (hasIllegalOffsets) {
                offsetAttribute.setOffset(savedStartOffset, savedEndOffset);
            } else {
                offsetAttribute.setOffset(startOffset, endOffset);
            }
            posIncAttribute.setPositionIncrement(position(true));
            typeAttribute.setType(savedType);
            accumPosInc = 0;
        }

        /**
         * Determines if the concatenation is empty
         *
         * @return {@code true} if the concatenation is empty, {@code false} otherwise
         */
        boolean isEmpty() {
            return buffer.length() == 0;
        }

        /**
         * Clears the concatenation and resets its state
         */
        void clear() {
            buffer.setLength(0);
            startOffset = endOffset = type = subwordCount = 0;
        }

        /**
         * Convenience method for the common scenario of having to write the concetenation and then clearing its state
         */
        void writeAndClear() {
            write();
            clear();
        }
    }
    // questions:
    // negative numbers?  -42 indexed as just 42?
    // dollar sign?  $42
    // percent sign?  33%
    // downsides:  if source text is "powershot" then a query of "PowerShot" won't match!
}
