/*
 * Copyright (c) 2013 Tah Wei Hoon.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License Version 2.0,
 * with full text available at http://www.apache.org/licenses/LICENSE-2.0.html
 *
 * This software is provided "as is". Use at your own risk.
 */
package com.mrikso.codeeditor.util;

import com.mrikso.codeeditor.lang.Language;
import com.mrikso.codeeditor.lang.LanguageNonProg;

import java.util.ArrayList;
import java.util.List;

/**
 * Does lexical analysis of a text for C-like languages.
 * The programming language syntax used is set as a static class variable.
 */
public class Lexer {
    public final static int UNKNOWN = -1;
    public final static int NORMAL = 0;
    public final static int KEYWORD = 1;
    public final static int OPERATOR = 2;
    public final static int NAME = 3;
    public final static int NUMBER = 4;
    /**
     * A word that starts with a special symbol, inclusive.
     * Examples:
     * :ruby_symbol
     */
    public final static int SINGLE_SYMBOL_WORD = 10;
    /**
     * Tokens that extend from a single start symbol, inclusive, until the end of line.
     * Up to 2 types of symbols are supported per language, denoted by A and B
     * Examples:
     * #include "myCppFile"
     * #this is a comment in Python
     * %this is a comment in Prolog
     */
    public final static int SINGLE_SYMBOL_LINE_A = 20;
    public final static int SINGLE_SYMBOL_LINE_B = 21;
    /**
     * Tokens that extend from a two start symbols, inclusive, until the end of line.
     * Examples:
     * //this is a comment in C
     */
    public final static int DOUBLE_SYMBOL_LINE = 30;
    /**
     * Tokens that are enclosed between a start and end sequence, inclusive,
     * that can span multiple lines. The start and end sequences contain exactly
     * 2 symbols.
     * Examples:
     * {- this is a...
     * ...multi-line comment in Haskell -}
     */
    public final static int DOUBLE_SYMBOL_DELIMITED_MULTILINE = 40;
    /**
     * Tokens that are enclosed by the same single symbol, inclusive, and
     * do not span over more than one line.
     * Examples: 'c', "hello world"
     */
    public final static int SINGLE_SYMBOL_DELIMITED_A = 50;
    public final static int SINGLE_SYMBOL_DELIMITED_B = 51;
    private final static int MAX_KEYWORD_LENGTH = 31;
    private static Language _globalLanguage = LanguageNonProg.getInstance();
    LexCallback _callback = null;
    private DocumentProvider _hDoc;
    private LexThread _workerThread = null;
    public Lexer(LexCallback callback) {
        _callback = callback;
    }

    synchronized public static Language getLanguage() {
        return _globalLanguage;
    }

    synchronized public static void setLanguage(Language lang) {
        _globalLanguage = lang;
    }

    public void tokenize(DocumentProvider hDoc) {
        if (!Lexer.getLanguage().isProgLang()) {
            return;
        }

        //tokenize will modify the state of hDoc; make a copy
        setDocument(new DocumentProvider(hDoc));
        if (_workerThread == null) {
            _workerThread = new LexThread(this);
            _workerThread.start();
        } else {
            _workerThread.restart();
        }
    }

    void tokenizeDone(List<Pair> result) {
        if (_callback != null) {
            _callback.lexDone(result);
        }
        _workerThread = null;
    }

    public void cancelTokenize() {
        if (_workerThread != null) {
            _workerThread.abort();
            _workerThread = null;
        }
    }

    public synchronized DocumentProvider getDocument() {
        return _hDoc;
    }

    public synchronized void setDocument(DocumentProvider hDoc) {
        _hDoc = hDoc;
    }


    public interface LexCallback {
        public void lexDone(List<Pair> results);
    }

    private class LexThread extends Thread {
        private final Lexer _lexManager;
        /**
         * can be set by another thread to stop the scan immediately
         */
        private final Flag _abort;
        private boolean rescan = false;
        /**
         * A collection of Pairs, where Pair.first is the start
         * position of the token, and Pair.second is the type of the token.
         */
        private ArrayList<Pair> _tokens;

        public LexThread(Lexer p) {
            _lexManager = p;
            _abort = new Flag();
        }

        @Override
        public void run() {
            do {
                rescan = false;
                _abort.clear();
                tokenize();
            }
            while (rescan);

            if (!_abort.isSet()) {
                // lex complete
                _lexManager.tokenizeDone(_tokens);
            }
        }

        public void restart() {
            rescan = true;
            _abort.set();
        }

        public void abort() {
            _abort.set();
        }

        /**
         * Scans the document referenced by _lexManager for tokens.
         * The result is stored internally.
         */
        public void tokenize() {
            DocumentProvider hDoc = getDocument();
            Language language = Lexer.getLanguage();
            ArrayList<Pair> tokens = new ArrayList<Pair>();

            if (!language.isProgLang()) {
                tokens.add(new Pair(0, NORMAL));
                _tokens = tokens;
                return;
            }

            char[] candidateWord = new char[MAX_KEYWORD_LENGTH];
            int currentCharInWord = 0;

            int spanStartPosition = 0;
            int workingPosition = 0;
            int state = UNKNOWN;
            char prevChar = 0;

            hDoc.seekChar(0);
            while (hDoc.hasNext() && !_abort.isSet()) {
                char currentChar = hDoc.next();

                switch (state) {
                    case UNKNOWN: //fall-through
                    case NORMAL: //fall-through
                    case KEYWORD: //fall-through
                    case SINGLE_SYMBOL_WORD:
                        int pendingState = state;
                        boolean stateChanged = false;
                        if (language.isLineStart(prevChar, currentChar)) {
                            pendingState = DOUBLE_SYMBOL_LINE;
                            stateChanged = true;
                        } else if (language.isMultilineStartDelimiter(prevChar, currentChar)) {
                            pendingState = DOUBLE_SYMBOL_DELIMITED_MULTILINE;
                            stateChanged = true;
                        } else if (language.isDelimiterA(currentChar)) {
                            pendingState = SINGLE_SYMBOL_DELIMITED_A;
                            stateChanged = true;
                        } else if (language.isDelimiterB(currentChar)) {
                            pendingState = SINGLE_SYMBOL_DELIMITED_B;
                            stateChanged = true;
                        } else if (language.isLineAStart(currentChar)) {
                            pendingState = SINGLE_SYMBOL_LINE_A;
                            stateChanged = true;
                        } else if (language.isLineBStart(currentChar)) {
                            pendingState = SINGLE_SYMBOL_LINE_B;
                            stateChanged = true;
                        }


                        if (stateChanged) {
                            if (pendingState == DOUBLE_SYMBOL_LINE ||
                                    pendingState == DOUBLE_SYMBOL_DELIMITED_MULTILINE) {
                                // account for previous char
                                spanStartPosition = workingPosition - 1;
								//TODO consider less greedy approach and avoid adding token for previous char
                                if (tokens.get(tokens.size() - 1).getFirst() == spanStartPosition) {
                                    tokens.remove(tokens.size() - 1);
                                }
                            } else {
                                spanStartPosition = workingPosition;
                            }

                            // If a span appears mid-word, mark the chars preceding
                            // it as NORMAL, if the previous span isn't already NORMAL
                            if (currentCharInWord > 0 && state != NORMAL) {
                                tokens.add(new Pair(workingPosition - currentCharInWord, NORMAL));
                            }

                            state = pendingState;
                            tokens.add(new Pair(spanStartPosition, state));
                            currentCharInWord = 0;
                        } else if (language.isWhitespace(currentChar) || language.isOperator(currentChar)) {
                            if (currentCharInWord > 0) {
                                // full word obtained; mark the beginning of the word accordingly
                                if (language.isWordStart(candidateWord[0])) {
                                    spanStartPosition = workingPosition - currentCharInWord;
                                    state = SINGLE_SYMBOL_WORD;
                                    tokens.add(new Pair(spanStartPosition, state));
                                } else if (language.isKeyword(new String(candidateWord, 0, currentCharInWord))) {
                                    spanStartPosition = workingPosition - currentCharInWord;
                                    state = KEYWORD;
                                    tokens.add(new Pair(spanStartPosition, state));
                                } else if (state != NORMAL) {
                                    spanStartPosition = workingPosition - currentCharInWord;
                                    state = NORMAL;
                                    tokens.add(new Pair(spanStartPosition, state));
                                }
                                currentCharInWord = 0;
                            }

                            // mark operators as normal
                            if (state != NORMAL && language.isOperator(currentChar)) {
                                state = NORMAL;
                                tokens.add(new Pair(workingPosition, state));
                            }
                        } else if (currentCharInWord < MAX_KEYWORD_LENGTH) {
                            // collect non-whitespace chars up to MAX_KEYWORD_LENGTH
                            candidateWord[currentCharInWord] = currentChar;
                            currentCharInWord++;
                        }
                        break;


                    case DOUBLE_SYMBOL_LINE: // fall-through
                    case SINGLE_SYMBOL_LINE_A: // fall-through
                    case SINGLE_SYMBOL_LINE_B:
                        if (currentChar == '\n') {
                            state = UNKNOWN;
                        }
                        break;


                    case SINGLE_SYMBOL_DELIMITED_A:
                        if ((language.isDelimiterA(currentChar) || currentChar == '\n')
                                && !language.isEscapeChar(prevChar)) {
                            state = UNKNOWN;
                        }
                        // consume escape of the escape character by assigning
                        // currentChar as something else so that it would not be
                        // treated as an escape char in the next iteration
                        else if (language.isEscapeChar(currentChar) && language.isEscapeChar(prevChar)) {
                            currentChar = ' ';
                        }
                        break;


                    case SINGLE_SYMBOL_DELIMITED_B:
                        if ((language.isDelimiterB(currentChar) || currentChar == '\n')
                                && !language.isEscapeChar(prevChar)) {
                            state = UNKNOWN;
                        }
                        // consume escape of the escape character by assigning
                        // currentChar as something else so that it would not be
                        // treated as an escape char in the next iteration
                        else if (language.isEscapeChar(currentChar)
                                && language.isEscapeChar(prevChar)) {
                            currentChar = ' ';
                        }
                        break;

                    case DOUBLE_SYMBOL_DELIMITED_MULTILINE:
                        if (language.isMultilineEndDelimiter(prevChar, currentChar)) {
                            state = UNKNOWN;
                        }
                        break;

                    default:
                        TextWarriorException.fail("Invalid state in TokenScanner");
                        break;
                }
                ++workingPosition;
                prevChar = currentChar;
            }
            // end state machine


            if (tokens.isEmpty()) {
                // return value cannot be empty
                tokens.add(new Pair(0, NORMAL));
            }

            _tokens = tokens;
        }


    }//end inner class
}
