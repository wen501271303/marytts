/**
 * Copyright 2007 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * Permission is hereby granted, free of charge, to use and distribute
 * this software and its documentation without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of this work, and to
 * permit persons to whom this work is furnished to do so, subject to
 * the following conditions:
 * 
 * 1. The code must retain the above copyright notice, this list of
 *    conditions and the following disclaimer.
 * 2. Any modifications must be clearly marked as such.
 * 3. Original authors' names are not deleted.
 * 4. The authors' names are not used to endorse or promote products
 *    derived from this software without specific prior written
 *    permission.
 *
 * DFKI GMBH AND THE CONTRIBUTORS TO THIS WORK DISCLAIM ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS, IN NO EVENT SHALL DFKI GMBH NOR THE
 * CONTRIBUTORS BE LIABLE FOR ANY SPECIAL, INDIRECT OR CONSEQUENTIAL
 * DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR
 * PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS
 * ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */

package marytts.tools.dbselection;


import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;
import java.util.HashMap;

import marytts.client.MaryClient;
import marytts.client.http.MaryHttpClient;
import marytts.datatypes.MaryData;
import marytts.datatypes.MaryDataType;
import marytts.features.FeatureDefinition;
import marytts.htsengine.HTSModel;
import marytts.util.dom.MaryDomUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.sun.speech.freetts.Utterance;



/**
 * Takes text and converts to features
 * Needs a running Mary server
 * 
 * @author Anna Hunecke
 *
 */
public class FeatureMakerMaryServer{
    // locale
    private static String locale;
	//the Mary Client connected to the server
	protected static MaryClient mary;
    //protected static MaryHttpClient mary;
	//stores result of credibility check for current sentence
	protected static boolean usefulSentence;
    protected static boolean unknownWords;
    protected static boolean strangeSymbols;
    
    //feature definition
	protected static FeatureDefinition featDef;
    protected static Vector<String> selectionFeature;
    protected static int[] selectionFeatureIndex;
    
    // log file
	protected static String logFileName;
    //host of the Mary server
	protected static String maryHost;
	//port of the Mary server
	protected static String maryPort;

    //if true, credibility is strict, else crebibility is lax
	protected static boolean strictCredibility;

    protected static int numSentences = 0;
    protected static int numUnreliableSentences = 0;
    
    protected static DBHandler wikiToDB;
    //  mySql database 
    private static String mysqlHost;
    private static String mysqlDB;
    private static String mysqlUser;
    private static String mysqlPasswd;
    
	public static void main(String[] args)throws Exception{
			
        String dateStringIni="";
        String dateStringEnd="";
//        DateFormat fullDate = new SimpleDateFormat("dd_MM_yyyy_HH:mm:ss");
//        Date dateIni = new Date();
//        String dateStringIni = fullDate.format(dateIni);
        
		/* check the arguments */
		if (!readArgs(args)){
			printUsage();
			return;
		}
  
        System.out.println("\nFeatureMaker started...");
		/* Start the Mary client */
		System.setProperty("server.host", maryHost);
		System.setProperty("server.port", maryPort);
		//mary = new MaryHttpClient();
        mary = new MaryClient();
		
        /* Here the DB connection for reliable sentences is open */
         wikiToDB = new DBHandler(locale);
         wikiToDB.createDBConnection(mysqlHost,mysqlDB,mysqlUser,mysqlPasswd);
         //wikiToDB.createDBConnection("penguin.dfki.uni-sb.de","MaryDBSelector","MaryDBSel_admin","p4rpt3jr");
         // check if tables exist
         wikiToDB.createDataBaseSelectionTable();
         
         // Get the set of id for unprocessed records in clean_text
         // this will be useful when the process is stoped and then resumed
         System.out.println("\nGetting list of unprocessed clean_text records from wikipedia...");
         String textId[];
         textId = wikiToDB.getUnprocessedTextIds();
         System.out.println("Number of clean_text records to process: " + textId.length);
         String text;
         
		
		/* loop over the text records in clean_text table of wiki */
        // once procesed the clean_text records are marked as processed=true, so here retrieve
        // the next clean_text record untill all are processed.
		System.out.println("Looping over clean_text records from wikipedia...\n");       
        PrintWriter pw = new PrintWriter(new FileWriter(new File(logFileName)));
        
        Vector<String> sentenceList;  // this will be the list of sentences in each clean_text
        int i, j;
        for(i=0; i<textId.length; i++){
          // get next unprocessed text  
          text = wikiToDB.getCleanText(textId[i]);
          System.out.println("Processing text id=" + textId[i] + " text length=" + text.length());
          sentenceList = splitIntoSentences(text, textId[i], pw);
        
          if( sentenceList != null ) {
              
		  int index=0;			
          // loop over the sentences
          int numSentencesInText=0;
          String newSentence;
          byte feas[];  // for directly saving a vector of bytes as BLOB in mysql DB
          for(j=0; j<sentenceList.size(); j++) {
			newSentence = sentenceList.elementAt(j);
            MaryData d = processSentence(newSentence,textId[i]);
		    if (d!=null){
			  // get the features of the sentence  
			  feas = getFeatures(d);     
              // Insert in the database the new sentence and its features.
              numSentencesInText++;
              wikiToDB.insertSentence(newSentence,feas, true, false, false, Integer.parseInt(textId[i]));
              feas = null;
            }        		
	      }//end of loop over list of sentences
          sentenceList.clear();
          sentenceList=null;

          numSentences += numSentencesInText;
          pw.println("Inserted " + numSentencesInText + " sentences from text id=" + textId[i] 
                                 + " (Total reliable = "+ numSentences+")\n");
          System.out.println("Inserted " + numSentencesInText + " sentences from text id=" 
                             + textId[i] + " (Total reliable = "+ numSentences+") \n");
          
          }
                         
		} //end of loop over articles  
        
        
        wikiToDB.closeDBConnection();
        
//        Date dateEnd = new Date();
//        dateStringEnd = fullDate.format(dateEnd);
        pw.println("numSentencesInText;=" + numSentences);
        pw.println("Start time:" + dateStringIni + "  End time:" + dateStringEnd);
        
        pw.close(); 
        
		System.out.println("Done");
	}//end of main method
	
	/**
	 * Print usage of this program 
	 *
	 */
	protected static void printUsage(){
		System.out.println("Usage:\n"
				+"java FeatureMakerMaryServer -locale en_US -mysqlHost host -mysqlUser user -mysqlPasswd passwd -mysqlDB wikiDB\n"
                +"  default/optional: [-maryHost localhost -maryPort 59125 -strictCredibility true]\n"
                +"  optional: [-strictCredibility [strict|lax] -log logFileName]\n"
                +"  optional: [-featuresForSelection phoneme,next_phoneme,selection_prosody,..] (features separated by ,) \n\n"
            	+"  -credibility [strict|lax]: Setting that determnines what kind of sentences \n"
				+"  are regarded as credible. There are two settings: strict and lax. With \n"
				+"  setting strict, only those sentences that contain words in the lexicon \n"
				+"  or words that were transcribed by the preprocessor are regarded as credible; \n"
				+"  the other sentences as unreliable. With setting lax, also those words that \n"
				+"  are transcribed with the Denglish and the compound module are regarded credible. \n\n");
                
	}
	
   private static void printParameters(){
        System.out.println("FeatureMakerMaryServer parameters:" +
        "\n  -locale " + locale +        
        "\n  -maryHost " + maryHost +
        "\n  -maryPort " + maryPort +
        "\n  -mysqlHost " + mysqlHost +
        "\n  -mysqlUser " + mysqlUser +
        "\n  -mysqlPasswd " + mysqlPasswd +
        "\n  -mysqlDB " + mysqlDB);
        
        if( strictCredibility )
          System.out.println("  -strictCredibility true");
        else
          System.out.println("  -strictCredibility false");  
       
        System.out.print("  -featuresForselection ");
        int i=0;
        for(i=0; i<selectionFeature.size()-1; i++)
          System.out.print(selectionFeature.elementAt(i) + ",");
        System.out.println(selectionFeature.elementAt(i));
    }
        
    
	/**
	 * Read and parse the command line args
	 * 
	 * @param args the args
	 * @return true, if successful, false otherwise
	 */
	protected static boolean readArgs(String[] args){
		//initialise default values		
		logFileName = "./featureMaker.log";
		maryHost = "localhost";
		maryPort = "59125";
        locale = "en_US";
		strictCredibility = true;
        featDef = null;
        selectionFeature = new Vector<String>();
        selectionFeature.add("phoneme");
        selectionFeature.add("next_phoneme");
        selectionFeature.add("selection_prosody");
        
		//now parse the args
        if (args.length >= 10){
          for(int i=0; i<args.length; i++) { 
			
            if (args[i].equals("-locale") && args.length>=i+1 )
              locale = args[++i];  
			          
            else if (args[i].equals("-maryHost") && args.length>=i+1 )
              maryHost = args[++i];
            
            else if (args[i].equals("-maryPort") && args.length>=i+1 )
              maryPort = args[++i];
			
            else if (args[i].equals("-credibility") && args.length>=i+1){
			  String credibilitySetting = args[++i];
			  if (credibilitySetting.equals("strict"))
				strictCredibility = true;
			  else {
				if (credibilitySetting.equals("lax"))
					strictCredibility = false;
			    else 
				  System.out.println("Unknown argument for credibility " +credibilitySetting);
			  }
            }
            
            else if(args[i].contentEquals("-featuresForSelection") && args.length >= (i+1) ){
              selectionFeature.clear();  
              String selection = args[++i];
              String feas[] = selection.split(",");
              for(int k=0; k<feas.length; k++)
                  selectionFeature.add(feas[k]);             
            }
            
            // mysql database parameters
            else if(args[i].contentEquals("-mysqlHost") && args.length >= (i+1) )
              mysqlHost = args[++i];
                
            else if(args[i].contentEquals("-mysqlUser") && args.length >= (i+1) )
              mysqlUser = args[++i];
                   
            else if(args[i].contentEquals("-mysqlPasswd") && args.length >= (i+1) )
              mysqlPasswd = args[++i];
                 
            else if(args[i].contentEquals("-mysqlDB") && args.length >= (i+1) )
              mysqlDB = args[++i];
            
            else if (args[i].equals("-logFile") && args.length>=i+1 )
              logFileName = args[++i];
            
            else { //unknown argument
              System.out.println("\nOption not known: " + args[i]);
              return false;
            }
                
            
          }	
		} else  // arguments less than 10
			return false;

        printParameters();
		return true;
	}
	
	
	/**
	 * Process one sentences from text to target features
	 * 
	 * @param nextSentence the sentence
	 * @param filename the file containing the sentence
	 * @return the result of the processing as MaryData object
	 */
	protected static MaryData processSentence(String nextSentence, String textId){
		//do a bit of normalization
        StringBuffer docBuf = null;
		nextSentence = nextSentence.replaceAll("\\\\","").trim();
		nextSentence = nextSentence.replaceAll("\\s/\\s","").trim();
		nextSentence = nextSentence.replaceAll("^/\\s","").trim();
		MaryData d = null;
		try{                    
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			//process and dump
			mary.process(nextSentence, "TEXT","TARGETFEATURES", "en_US", null, "hsmm-slt", os);
			//read into mary data object                
			//d = new MaryData(MaryDataType.get("TARGETFEATURES"), null);
            //d = new MaryData(MaryDataType.TARGETFEATURES, Locale.US);
            d = new MaryData(MaryDataType.TARGETFEATURES, null);
            
			d.readFrom(new ByteArrayInputStream(os.toByteArray()));			
		} catch (Exception e){
			e.printStackTrace();
			if (d!=null){  
				if (d.getPlainText()!=null){
					System.out.println("Error processing sentence "
							+textId
							+": \""+nextSentence+"\":\n"+d.getPlainText()
							+"; skipping sentence");
				} else {
					if (d.getDocument() != null){
						docBuf = new StringBuffer();
						getXMLAsString(d.getDocument(),docBuf);
						System.out.println("Error processing sentence "
								+": \""+nextSentence+"\":\n"+docBuf.toString()
								+"; skipping sentence");
					} else {
						if (d.getUtterances() != null){
							List utterances = d.getUtterances();
							Iterator it = utterances.iterator();
							System.out.println("Error processing sentence "
									+": \""+nextSentence+"\":\n");
							while (it.hasNext()) {
								Utterance utterance = (Utterance) it.next();
								StringWriter sw = new StringWriter();
								PrintWriter pw = new PrintWriter(sw);
								utterance.dump(pw, 2, "", true); // padding, justRelations
								System.out.println(sw.toString());
							}
							System.out.println("; skipping sentence");
						}else {
							System.out.println("Error processing sentence "
									+textId
									+": \""+nextSentence+"\"; skipping sentence");                        
						}
					}
				}
			} else {
				System.out.println("Error processing sentence from textId="
						+textId
						+": \""+nextSentence+"\"; skipping sentence");                        
			}
			return null;
		}
		catch (AssertionError ae){
			ae.printStackTrace();
			System.out.println("Error processing sentence from textId="
					+textId
					+": \""+nextSentence+"\"; skipping sentence");
			return null;
		}
        
        docBuf = null;
		return d;
		
		
		
	}
	
	
	
	/**
	 * Phonemise the given document with 
	 * the help of JPhonemiser
     * 
     * g2p_method
     * "contains-unknown-words" or "contains-strange-symbols",
	 * 
	 * @param d
	 * @return 0 if the sentence is useful
     *         1 if the sentence contains unknownWords
     *         2 if the sentence contains strangeSymbols
	 */
	protected static int checkCredibility(Element t){
        
		//boolean newUsefulSentence = true;
        int newUsefulSentence = 0;
        
		if (t.hasAttribute("ph")){
			//we have a transcription
			if (t.hasAttribute("g2p_method")) {
				//check method of transcription
				String method = t.getAttribute("g2p_method");
				if (!method.equals("lexicon") && !method.equals("userdict")){
					if (strictCredibility){
						//method other than lexicon or userdict -> unreliable
                        newUsefulSentence = 1;
                       
					} else {
						//lax credibility criterion
						if (!method.equals("phonemiseDenglish") && !method.equals("compound")){
							//method other than lexicon, userdict, phonemiseDenglish 
							//or compound -> unreliable
                            newUsefulSentence = 1;
                            
						} //else method is phonemiseDenglish or compound -> credible						
					}
				}// else method is lexicon or userdict -> credible				
			} //else no method -> preprocessed -> credible			
		} else {      
			//we dont have a transcription
			if (t.hasAttribute("pos") && !t.getAttribute("pos").startsWith("$")){					
				//no transcription given -> unreliable	
                newUsefulSentence = 2;      
			} //else punctuation -> credible
		} 
		return newUsefulSentence;
	}
		
		
		
		/**
		 * Process the target features
		 * and print them to the given file 
		 * 
		 * @param filename the file to print the features to
		 * @param d the target features as Mary Data object
		 * @throws Exception
		 */
		protected static byte[] getFeatures(MaryData d)throws Exception{
            
            BufferedReader featsDis = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(d.getPlainText().getBytes())));    
			String line;
            // The first time the feaDef is null, so then load the featureDefinition and the indexes of the features
            // used for selection. 
			if (featDef == null){  
 			  featDef = new FeatureDefinition(featsDis,false);
              // find the indexes of the features for selection
              selectionFeatureIndex = new int[selectionFeature.size()];
              for(int i=0; i<selectionFeature.size(); i++){
                 selectionFeatureIndex[i] = featDef.getFeatureIndex(selectionFeature.elementAt(i));  
              }
            }  else { // once the featDef has been load just skip the first part of the featDis
				//read until an empty line occurs
				while ((line = featsDis.readLine()) != null){
					if (line.equals("")) break;
				}
			}
			
			// loop over the feature vectors 
			List<String> featureLines = new ArrayList<String>();
			while ((line = featsDis.readLine()) != null){
				if (line.equals("")) break;
				featureLines.add(line);
			}
			int numLines = featureLines.size();
			//System.out.println("num vectors = "+numLines);
			byte[][] featVects = new byte[numLines][];
			for (int i=0;i<numLines;i++){
				line = (String) featureLines.get(i);
				String[] fv = line.split(" ");
				byte[] nextVector = new byte[4];
                for(int j=0; j<selectionFeature.size(); j++)
                  nextVector[j] =  featDef.getFeatureValueAsByte(selectionFeature.elementAt(j),fv[selectionFeatureIndex[j]]);
                featVects[i] = nextVector;
				
			} //end of while-loop over the feature vectors
              
            // create a byte vector with the reults
            //System.out.println("number of lines=" + numLines);
            byte feasVector[] = new byte[(numLines*4)];
            
			for (int n=0,i=0;i<featVects.length;i++){
				byte[] nextFeatVects = featVects[i];
				if (nextFeatVects == null){
					System.out.println("nextFeatVects are null at index "+i);
				}
               for(int m=0; m<selectionFeature.size(); m++)
                   feasVector[n++] = nextFeatVects[m];
    		}
            
	        return feasVector;
		}
		
		
	
        
		/**
		 * Split the text
		 * into separate sentences
		 * 
		 * @param file the file
		 * @return true, if successful
		 * @throws Exception
		 */
		protected static Vector<String> splitIntoSentences(String text, String id, PrintWriter pw)throws Exception{
            
            Vector<String> sentenceList = null;
            StringBuffer sentence;
            //index2sentences = new TreeMap<Integer,String>();
			
            Document doc = phonemiseText(text, id);
			//if (doc == null) return false;
            
            if (doc != null) {
            sentenceList = new Vector<String>();    
			NodeList sentences = doc.getElementsByTagName("s");   
			
            int sentenceIndex = 1;
            int unrelSentences=0;
			for (int j=0;j<sentences.getLength();j++){
				Node nextSentence = sentences.item(j);
				//ignore all non-element children
				if (!(nextSentence instanceof Element)) continue; 
				sentence = null;
				//get the tokens
				NodeList tokens = nextSentence.getChildNodes();
				usefulSentence = true;
                unknownWords = false;
                strangeSymbols = false;
				for (int k=0;k<tokens.getLength();k++){
					Node nextToken = tokens.item(k);
					//ignore all non-element children
					if ( (nextToken instanceof Element) ) 
					  sentence = collectTokens(nextToken, sentence);                            
				}
                //System.out.println(sentence);
				if (sentence!=null){
					if (usefulSentence){	
						//store sentence in sentence map
						//index2sentences.put(new Integer(sentenceIndex),sentence.toString());
                        // check if the sentence is not . 
                        if( !sentence.toString().contentEquals(".") ){
                         sentenceList.add(sentence.toString());
                         //System.out.println("sentence=" + sentence.toString() + "\n");
                        }
					} else {
						//just print useless sentence to log file
						//System.out.println(filename+"; "+sentenceIndex+": "+sentence
						//		+" : is unreliable");
                        
                        unrelSentences++;
                        
                        //System.out.println("Inserting unreliable sentence:");
                         
                        // Here the reason why is unreliable can be added to the DB.
                        // for the moment there is just one field reliable=false in this case.
                        wikiToDB.insertSentence(sentence.toString(), null, usefulSentence, unknownWords, strangeSymbols, Integer.parseInt(id));
					}
					sentenceIndex++;
                   
				} else {
					//ignore
					//System.out.println("NULL SENTENCE!!!");
				}
			} 
            numUnreliableSentences += unrelSentences;
            pw.println("Inserted " + unrelSentences + " sentences from text id=" + id + " (Total unreliable = " + numUnreliableSentences + ")");
            System.out.println("Inserted " + unrelSentences + " sentences from text id=" + id + " (Total unreliable = " + numUnreliableSentences + ")");
            
            } 
            
            sentence = null;
			return sentenceList;
		}
		
      
		/**
		 * Process the given text with the MaryClient
		 * from Text to Chunked
		 * 
		 * @param textString the text to process
		 * @return the resulting XML-Document
		 * @throws Exception
		 */
		protected static Document phonemiseText(String textString, String id) throws Exception{
			try{
				ByteArrayOutputStream os = new ByteArrayOutputStream();
				//process and dump
				mary.process(textString, "TEXT","PHONEMES", "en_US", null, "hsmm-slt", os);
                
                //read into mary data object                
				//MaryData maryData = new MaryData(MaryDataType.PHONEMES, Locale.GERMAN);
                //MaryData maryData = new MaryData(MaryDataType.PHONEMES, Locale.US);
                MaryData maryData = new MaryData(MaryDataType.PHONEMES, Locale.US);
                
				maryData.readFrom(new ByteArrayInputStream(os.toByteArray()));
               
				return maryData.getDocument();
			} catch (Exception e){
				e.printStackTrace();
                System.out.println("PhonemiseText: problem processing text id=" + id);
				return null;            
			}
			
		}
		
		/**
		 * Collect the tokens of a sentence
		 * 
		 * @param nextToken the Node to start from
         * checkCredibility returns
         *  0 if the sentence is useful
         *  1 if the sentence contains unknownWords (so the sentence is not useful)
         *  2 if the sentence contains strangeSymbols (so the sentence is not useful)
		 */
		protected static StringBuffer collectTokens(Node nextToken, StringBuffer sentence){
            int credibility = 0; 
            String tokenText, word;
			String name = nextToken.getLocalName();
			if (name.equals("t")){
				if ( ( credibility = checkCredibility((Element) nextToken) ) > 0 ){
					//memorize that we found unreliable sentence
					usefulSentence = false;
                    if(credibility == 1)
                      unknownWords = true;
                    else if(credibility == 2)
                      strangeSymbols = true;  
				}
				if (sentence == null){
					sentence = new StringBuffer();
					//first word of the sentence
                     word = MaryDomUtils.tokenText((Element)nextToken);                   
					 sentence.append(word);
                     
				} else {
					String pos = ((Element)nextToken).getAttribute("pos");
					if (pos.startsWith("$")){
						//punctuation
						tokenText = MaryDomUtils.tokenText((Element)nextToken);
						//just append without whitespace
						sentence.append(tokenText);
						
					} else {
						//normal word, append a whitespace before it
                        word = MaryDomUtils.tokenText((Element)nextToken);
                        //System.out.println("word=" + word);
                        sentence.append(" " + word);
					}
				}
			} else {
				if (name.equals("mtu")){
					//get the tokens
					NodeList mtuTokens = nextToken.getChildNodes();
					for (int l=0;l<mtuTokens.getLength();l++){
						Node nextMTUToken = mtuTokens.item(l);
						//ignore all non-element children
						if (!(nextMTUToken instanceof Element)) continue; 
						collectTokens(nextMTUToken, sentence);
					}
				}
				
			}
            
            return sentence;
		}
		
		/**
		 * Convert the given xml-node and its subnodes to Strings
		 * and collect them in the given Stringbuffer
		 * 
		 * @param motherNode the xml-node
		 * @param ppText the Stringbuffer
		 */
		protected static void getXMLAsString(Node motherNode,StringBuffer ppText){
			NodeList children = motherNode.getChildNodes();
			for (int i=0;i<children.getLength();i++){
				Node nextChild = children.item(i);
				String name = nextChild.getLocalName();
				if (name == null){
					continue;
				}          
				
				ppText.append("<"+name);
				if (nextChild instanceof Element){
					if (nextChild.hasAttributes()){
						NamedNodeMap atts = nextChild.getAttributes();
						for (int j=0;j<atts.getLength();j++){
							String nextAtt = atts.item(j).getNodeName();
							ppText.append(" "+nextAtt+"=\""
									+((Element)nextChild).getAttribute(nextAtt)
									+"\"");
						}
					}
					
				}
				if (name.equals("boundary")){
					ppText.append("/>\n");
					continue;
				}
				ppText.append(">\n");
				if (name.equals("t")){
					ppText.append(MaryDomUtils.tokenText((Element)nextChild)
							+"\n</t>\n");                
				} else {
					if (nextChild.hasChildNodes()){
						getXMLAsString(nextChild,ppText);
					} 
					ppText.append("</"+name+">\n");
				}
			}
		}
		  
		
	}