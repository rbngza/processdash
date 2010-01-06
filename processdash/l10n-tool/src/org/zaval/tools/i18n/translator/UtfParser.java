/* Generated By:JavaCC: Do not edit this line. UtfParser.java */
package org.zaval.tools.i18n.translator;

import java.io.*;
import java.util.*;

public class UtfParser implements UtfParserConstants {
    private String key = "default";
    private Hashtable ask;

    public Hashtable parse()
    throws ParseException
    {
        ask = new Hashtable();
        parseMain();
        return ask;
    }

  final public void parseMain() throws ParseException {
    label_1:
    while (true) {
      keyDef();

      label_2:
      while (true) {
        langPair();

        switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
        case STRING_LITERAL_1:
        case STRING_LITERAL_2:
          ;
          break;
        default:
          jj_la1[0] = jj_gen;
          break label_2;
        }
      }

      switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
      case KEY:
        ;
        break;
      default:
        jj_la1[1] = jj_gen;
        break label_1;
      }
    }

    jj_consume_token(0);
  }

  final public void keyDef() throws ParseException {
    String name;
    jj_consume_token(KEY);
    jj_consume_token(EQ);
    name = string();
                                key = name;
    jj_consume_token(DBC);
  }

  final public void langPair() throws ParseException {
    String lang, val;
    lang = string();

    jj_consume_token(EQ);
    val = string();
        ask.put(key + "!" + lang, val);
  }

  final public String string() throws ParseException {
    Token tok;
    switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
    case STRING_LITERAL_1:
      tok = jj_consume_token(STRING_LITERAL_1);
      break;
    case STRING_LITERAL_2:
      tok = jj_consume_token(STRING_LITERAL_2);
      break;
    default:
      jj_la1[2] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return tok.image.substring(1, tok.image.length()-1);}
    throw new Error("Missing return statement in function");
  }

  public UtfParserTokenManager token_source;
  JavaCharStream jj_input_stream;
  public Token token, jj_nt;
  private int jj_ntk;
  private int jj_gen;
  final private int[] jj_la1 = new int[3];
  final private int[] jj_la1_0 = {0x3000,0x200,0x3000,};

  public UtfParser(java.io.InputStream stream) {
    jj_input_stream = new JavaCharStream(stream, 1, 1);
    token_source = new UtfParserTokenManager(jj_input_stream);
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 3; i++) jj_la1[i] = -1;
  }

  public void ReInit(java.io.InputStream stream) {
    jj_input_stream.ReInit(stream, 1, 1);
    token_source.ReInit(jj_input_stream);
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 3; i++) jj_la1[i] = -1;
  }

  public UtfParser(java.io.Reader stream) {
    jj_input_stream = new JavaCharStream(stream, 1, 1);
    token_source = new UtfParserTokenManager(jj_input_stream);
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 3; i++) jj_la1[i] = -1;
  }

  public void ReInit(java.io.Reader stream) {
    jj_input_stream.ReInit(stream, 1, 1);
    token_source.ReInit(jj_input_stream);
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 3; i++) jj_la1[i] = -1;
  }

  public UtfParser(UtfParserTokenManager tm) {
    token_source = tm;
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 3; i++) jj_la1[i] = -1;
  }

  public void ReInit(UtfParserTokenManager tm) {
    token_source = tm;
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 3; i++) jj_la1[i] = -1;
  }

  final private Token jj_consume_token(int kind) throws ParseException {
    Token oldToken;
    if ((oldToken = token).next != null) token = token.next;
    else token = token.next = token_source.getNextToken();
    jj_ntk = -1;
    if (token.kind == kind) {
      jj_gen++;
      return token;
    }
    token = oldToken;
    jj_kind = kind;
    throw generateParseException();
  }

  final public Token getNextToken() {
    if (token.next != null) token = token.next;
    else token = token.next = token_source.getNextToken();
    jj_ntk = -1;
    jj_gen++;
    return token;
  }

  final public Token getToken(int index) {
    Token t = token;
    for (int i = 0; i < index; i++) {
      if (t.next != null) t = t.next;
      else t = t.next = token_source.getNextToken();
    }
    return t;
  }

  final private int jj_ntk() {
    if ((jj_nt=token.next) == null)
      return (jj_ntk = (token.next=token_source.getNextToken()).kind);
    else
      return (jj_ntk = jj_nt.kind);
  }

  private java.util.Vector jj_expentries = new java.util.Vector();
  private int[] jj_expentry;
  private int jj_kind = -1;

  final public ParseException generateParseException() {
    jj_expentries.removeAllElements();
    boolean[] la1tokens = new boolean[14];
    for (int i = 0; i < 14; i++) {
      la1tokens[i] = false;
    }
    if (jj_kind >= 0) {
      la1tokens[jj_kind] = true;
      jj_kind = -1;
    }
    for (int i = 0; i < 3; i++) {
      if (jj_la1[i] == jj_gen) {
        for (int j = 0; j < 32; j++) {
          if ((jj_la1_0[i] & (1<<j)) != 0) {
            la1tokens[j] = true;
          }
        }
      }
    }
    for (int i = 0; i < 14; i++) {
      if (la1tokens[i]) {
        jj_expentry = new int[1];
        jj_expentry[0] = i;
        jj_expentries.addElement(jj_expentry);
      }
    }
    int[][] exptokseq = new int[jj_expentries.size()][];
    for (int i = 0; i < jj_expentries.size(); i++) {
      exptokseq[i] = (int[])jj_expentries.elementAt(i);
    }
    return new ParseException(token, exptokseq, tokenImage);
  }

  final public void enable_tracing() {
  }

  final public void disable_tracing() {
  }

}