/*******************************************************************************
 * KOMORAN 3.0 - Korean Morphology Analyzer
 *
 * Copyright 2015 Shineware http://www.shineware.co.kr
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 * 	http://www.apache.org/licenses/LICENSE-2.0
 * 	
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package kr.co.shineware.nlp.komoran.core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.Character.UnicodeBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kr.co.shineware.nlp.komoran.constant.SCORE;
import kr.co.shineware.nlp.komoran.constant.SYMBOL;
import kr.co.shineware.nlp.komoran.core.model.Lattice;
import kr.co.shineware.nlp.komoran.core.model.LatticeNode;
import kr.co.shineware.nlp.komoran.core.model.Resources;
import kr.co.shineware.nlp.komoran.corpus.parser.CorpusParser;
import kr.co.shineware.nlp.komoran.corpus.parser.model.ProblemAnswerPair;
import kr.co.shineware.nlp.komoran.model.MorphTag;
import kr.co.shineware.nlp.komoran.model.ScoredTag;
import kr.co.shineware.nlp.komoran.model.Tag;
import kr.co.shineware.nlp.komoran.modeler.model.IrregularNode;
import kr.co.shineware.nlp.komoran.modeler.model.Observation;
import kr.co.shineware.nlp.komoran.parser.KoreanUnitParser;
import kr.co.shineware.util.common.model.Pair;
import kr.co.shineware.util.common.string.StringUtil;

public class Komoran {

	private Resources resources;
	private Observation userDic;
	private KoreanUnitParser unitParser;
	private Lattice lattice;

	private HashMap<String, List<Pair<String, String>>> fwd;

	private String prevPos;
	private String prevMorph;
	private int prevBeginIdx;

	public Komoran(String modelPath){
		this.resources = new Resources();
		this.load(modelPath);
		this.unitParser = new KoreanUnitParser();
	}

	public List<Pair<String,String>> analyzeWithSpacing(String sentence){
		List<Pair<String,String>> resultList = new ArrayList<>();
		this.lattice = new Lattice(this.resources);
		this.lattice.setUnitParser(this.unitParser);

		//연속된 숫자, 외래어, 기호 등을 파싱 하기 위한 버퍼
		this.prevPos = "";
		this.prevMorph = "";
		this.prevBeginIdx = 0;

		//자소 단위로 분할
		String jasoUnits = unitParser.parse(sentence);

		int length = jasoUnits.length();
		int prevStartSymbolIdx = -1;
		boolean inserted;
		for(int i=0; i<length; i++){
			//띄어쓰기인 경우
			if(jasoUnits.charAt(i) == ' '){
				//띄어쓰기에 <end> 노드 추가
				this.lattice.setLastIdx(i);
				inserted = this.lattice.appendEndNode();
				//이 부분에 대한 정제가 필요함
				if(!inserted){
					LatticeNode latticeNode = new LatticeNode(prevStartSymbolIdx+1,i,new MorphTag(jasoUnits.substring(prevStartSymbolIdx+1, i), SYMBOL.NA, this.resources.getTable().getId(SYMBOL.NA)),this.lattice.getNodeList(prevStartSymbolIdx).get(0).getScore());
					latticeNode.setPrevNodeIdx(0);
					this.lattice.appendNode(latticeNode);
					inserted = this.lattice.appendEndNode();
				}
				prevStartSymbolIdx = i;
			}
			this.continiousSymbolParsing(jasoUnits.charAt(i),i); //숫자, 영어, 외래어 파싱
			this.symbolParsing(jasoUnits.charAt(i),i); // 기타 심볼 파싱
			this.userDicParsing(jasoUnits.charAt(i),i); //사용자 사전 적용
			this.regularParsing(jasoUnits.charAt(i),i); //일반규칙 파싱
			this.irregularParsing(jasoUnits.charAt(i),i); //불규칙 파싱
			this.irregularExtends(jasoUnits.charAt(i),i); //불규칙 확장
		}
		
		this.consumeContiniousSymbolParserBuffer(jasoUnits);
		this.lattice.setLastIdx(jasoUnits.length());
		inserted = this.lattice.appendEndNode();
		if(!inserted){
			LatticeNode latticeNode = new LatticeNode(prevStartSymbolIdx+1,jasoUnits.length(),new MorphTag(jasoUnits.substring(prevStartSymbolIdx+1, jasoUnits.length()), SYMBOL.NA, this.resources.getTable().getId(SYMBOL.NA)),this.lattice.getNodeList(prevStartSymbolIdx).get(0).getScore());
			latticeNode.setPrevNodeIdx(0);
			this.lattice.appendNode(latticeNode);
			inserted = this.lattice.appendEndNode();
		}
//		this.lattice.printLattice();

		List<Pair<String,String>> shortestPathList = this.lattice.findPath();

		//미분석인 경우
		if(shortestPathList == null){
			resultList.add(new Pair<>(sentence,"NA"));
		}else{
			Collections.reverse(shortestPathList);
			resultList.addAll(shortestPathList);
		}

		return resultList;
	}

	public List<Pair<String,String>> analyze(String sentence){
		List<Pair<String,String>> resultList = new ArrayList<>();

		String[] tokens = sentence.split(" ");
		for (String token : tokens) {

			token = token.trim();
			if(token.length() == 0){
				return null;
			}

			//기분석 사전
			List<Pair<String,String>> fwdLookupResult = this.lookupFwd(token);
			if(fwdLookupResult != null){
				resultList.addAll(fwdLookupResult);
			}

			this.resources.getIrrTrie().getTrieDictionary().initCurrentNode();
			this.resources.getObservation().getTrieDictionary().initCurrentNode();
			if(this.userDic != null){
				this.userDic.getTrieDictionary().initCurrentNode();
			}

			this.lattice = new Lattice(this.resources);
			this.lattice.setUnitParser(this.unitParser);

			//연속된 숫자, 외래어, 기호 등을 파싱 하기 위한 버퍼
			this.prevPos = "";
			this.prevMorph = "";
			this.prevBeginIdx = 0;

			//자소 단위로 분할
			String jasoUnits = unitParser.parse(token);

			int length = jasoUnits.length();

			for(int i=0; i<length; i++){
				this.continiousSymbolParsing(jasoUnits.charAt(i),i); //숫자, 영어, 외래어 파싱
				this.symbolParsing(jasoUnits.charAt(i),i); // 기타 심볼 파싱
				this.userDicParsing(jasoUnits.charAt(i),i); //사용자 사전 적용
				this.regularParsing(jasoUnits.charAt(i),i); //일반규칙 파싱
				this.irregularParsing(jasoUnits.charAt(i),i); //불규칙 파싱
				this.irregularExtends(jasoUnits.charAt(i),i); //불규칙 확장
			}

			this.consumeContiniousSymbolParserBuffer(jasoUnits);

			this.lattice.setLastIdx(jasoUnits.length());
			this.lattice.appendEndNode();

			List<Pair<String,String>> shortestPathList = this.lattice.findPath();

			//미분석인 경우
			if(shortestPathList == null){
				resultList.add(new Pair<>(token,"NA"));
			}else{
				Collections.reverse(shortestPathList);
				resultList.addAll(shortestPathList);
			}		
		}

		return resultList;
	}

	private boolean symbolParsing(char jaso, int idx) {

		Character.UnicodeBlock unicodeBlock = Character.UnicodeBlock.of(jaso);
		//숫자
		if(Character.isDigit(jaso)){
			return false;
		}
		else if(unicodeBlock == Character.UnicodeBlock.BASIC_LATIN){
			//영어
			if (((jaso >= 'A') && (jaso <= 'Z')) || ((jaso >= 'a') && (jaso <= 'z'))) {
				return false;
			}
			else if(this.resources.getObservation().getTrieDictionary().getValue(""+jaso) != null){
				return false;
			}
			else if(jaso == ' '){
				return false;
			}
			//아스키 코드 범위 내에 사전에 없는 경우에는 기타 문자
			else{
				this.lattice.put(idx, idx+1, ""+jaso, SYMBOL.SW, this.resources.getTable().getId(SYMBOL.SW), SCORE.SW);
				return true;
			}
		}
		//한글
		else if(unicodeBlock == UnicodeBlock.HANGUL_COMPATIBILITY_JAMO 
				|| unicodeBlock == UnicodeBlock.HANGUL_JAMO
				|| unicodeBlock == UnicodeBlock.HANGUL_JAMO_EXTENDED_A
				||unicodeBlock == UnicodeBlock.HANGUL_JAMO_EXTENDED_B
				||unicodeBlock == UnicodeBlock.HANGUL_SYLLABLES){
			return false;
		}
		//일본어
		else if(unicodeBlock == UnicodeBlock.KATAKANA
				|| unicodeBlock == UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS){
			return false;
		}
		//중국어
		else if(UnicodeBlock.CJK_COMPATIBILITY.equals(unicodeBlock)				
				|| UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(unicodeBlock)
				|| UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(unicodeBlock)
				|| UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(unicodeBlock)
				|| UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(unicodeBlock)){
			return false;
		}
		//그 외 문자인 경우
		else{
			this.lattice.put(idx, idx+1, ""+jaso, SYMBOL.SW, this.resources.getTable().getId(SYMBOL.SW), SCORE.SW);
			return true;
		}
	}

	private boolean userDicParsing(char jaso, int curIndex) {
		//TRIE 기반의 사전 검색하여 형태소와 품사 및 품사 점수(observation)를 얻어옴
		Map<String, List<ScoredTag>> morphScoredTagsMap = this.getMorphScoredTagMapFromUserDic(jaso);

		if(morphScoredTagsMap == null){
			return false;
		}

		//형태소 정보만 얻어옴
		Set<String> morphes = this.getMorphes(morphScoredTagsMap);

		//각 형태소와 품사 정보를 lattice에 삽입
		for (String morph : morphes) {
			int beginIdx = curIndex-morph.length()+1;
			int endIdx = curIndex+1;

			//형태소에 대한 품사 및 점수(observation) 정보를 List 형태로 가져옴
			List<ScoredTag> scoredTags = morphScoredTagsMap.get(morph);
			for (ScoredTag scoredTag : scoredTags) {
				this.insertLattice(beginIdx,endIdx,morph,scoredTag,scoredTag.getScore());
			}
		}
		return true;		
	}

	private List<Pair<String, String>> lookupFwd(String token) {
		if(this.fwd == null){
			return null;
		}
		return this.fwd.get(token);
	}

	private void continiousSymbolParsing(char charAt, int i) {
		String curPos = "";
		if(StringUtil.isEnglish(charAt)){
			curPos = "SL";
		}else if(StringUtil.isNumeric(charAt)){
			curPos = "SN";
		}else if(StringUtil.isChinese(charAt)){
			curPos = "SH";
		}else if(StringUtil.isForeign(charAt)){
			curPos = "SL";
		}

		if(curPos.equals(this.prevPos)){
			this.prevMorph += charAt;
		}
		else{
			if(this.prevPos.equals("SL")){
				this.lattice.put(this.prevBeginIdx, i, this.prevMorph, this.prevPos,this.resources.getTable().getId(this.prevPos), SCORE.SL);
			}else if(this.prevPos.equals("SN")){
				this.lattice.put(this.prevBeginIdx, i, this.prevMorph, this.prevPos,this.resources.getTable().getId(this.prevPos), SCORE.SN);
			}else if(this.prevPos.equals("SH")){
				this.lattice.put(this.prevBeginIdx, i, this.prevMorph, this.prevPos,this.resources.getTable().getId(this.prevPos), SCORE.SH);
			}

			this.prevBeginIdx = i;
			this.prevMorph = ""+charAt;
			this.prevPos = curPos;
		}
	}

	private void consumeContiniousSymbolParserBuffer(String in) {
		if(this.prevPos.trim().length() != 0){
			if(this.prevPos.equals("SL")){
				this.lattice.put(this.prevBeginIdx, in.length(), this.prevMorph, this.prevPos,this.resources.getTable().getId(this.prevPos), SCORE.SL);
			}else if(this.prevPos.equals("SH")){
				this.lattice.put(this.prevBeginIdx, in.length(), this.prevMorph, this.prevPos,this.resources.getTable().getId(this.prevPos), SCORE.SH);
			}else if(this.prevPos.equals("SN")){
				this.lattice.put(this.prevBeginIdx, in.length(), this.prevMorph, this.prevPos,this.resources.getTable().getId(this.prevPos), SCORE.SN);
			}
		}
	}

	private void irregularExtends(char jaso, int curIndex) {
		List<LatticeNode> prevLatticeNodes = this.lattice.getNodeList(curIndex);
		if(prevLatticeNodes == null){
			;
		}else{
			Set<LatticeNode> extendedIrrNodeList = new HashSet<>();

			for (LatticeNode prevLatticeNode : prevLatticeNodes) {
				//불규칙 태그인 경우에 대해서만
				if( prevLatticeNode.getMorphTag().getTagId() == SYMBOL.IRREGULAR_ID ) {
					//마지막 형태소 정보를 얻어옴
					String lastMorph = prevLatticeNode.getMorphTag().getMorph();

					//불규칙의 마지막 형태소에 현재 자소 단위를 합쳤을 때 자식 노드가 있다면 계속 탐색 가능 후보로 처리 해야함
					if(this.resources.getObservation().getTrieDictionary().hasChild((lastMorph+jaso).toCharArray())){
						LatticeNode extendedIrregularNode = new LatticeNode();
						extendedIrregularNode.setBeginIdx(prevLatticeNode.getBeginIdx());
						extendedIrregularNode.setEndIdx(curIndex+1);
						extendedIrregularNode.setMorphTag(new MorphTag(prevLatticeNode.getMorphTag().getMorph()+jaso, SYMBOL.IRREGULAR, SYMBOL.IRREGULAR_ID));
						extendedIrregularNode.setPrevNodeIdx(prevLatticeNode.getPrevNodeIdx());
						extendedIrregularNode.setScore(prevLatticeNode.getScore());
						extendedIrrNodeList.add(extendedIrregularNode);
					}
					//불규칙의 마지막 형태소에 현재 자소 단위를 합쳐 점수를 얻어옴
					List<ScoredTag> lastScoredTags = this.resources.getObservation().getTrieDictionary().getValue(lastMorph+jaso);
					if(lastScoredTags == null){
						continue;
					}

					//얻어온 점수를 토대로 lattice에 넣음
					for (ScoredTag scoredTag : lastScoredTags) {
						this.lattice.put(prevLatticeNode.getBeginIdx(), curIndex+1, prevLatticeNode.getMorphTag().getMorph()+jaso,
								scoredTag.getTag(), scoredTag.getTagId(),scoredTag.getScore());
					}
				}
			}
			for (LatticeNode extendedIrrNode : extendedIrrNodeList) {
				this.lattice.appendNode(extendedIrrNode);
			}

		}
	}

	private boolean irregularParsing(char jaso, int curIndex) {
		//불규칙 노드들을 얻어옴
		Map<String,List<IrregularNode>> morphIrrNodesMap = this.getIrregularNodes(jaso);
		if(morphIrrNodesMap == null){
			return false;
		}

		//형태소 정보만 얻어옴
		Set<String> morphs = morphIrrNodesMap.keySet();
		for (String morph : morphs) {
			List<IrregularNode> irrNodes = morphIrrNodesMap.get(morph);
			int beginIdx = curIndex-morph.length()+1;
			int endIdx = curIndex+1;
			for (IrregularNode irregularNode : irrNodes) {
				this.insertLattice(beginIdx, endIdx, irregularNode);
			}
		}

		return true;
	}

	private void insertLattice(int beginIdx, int endIdx,
			IrregularNode irregularNode) {
		this.lattice.put(beginIdx, endIdx, irregularNode);		
	}

	private boolean regularParsing(char jaso,int curIndex) {
		//TRIE 기반의 사전 검색하여 형태소와 품사 및 품사 점수(observation)를 얻어옴
		Map<String, List<ScoredTag>> morphScoredTagsMap = this.getMorphScoredTagsMap(jaso);

		if(morphScoredTagsMap == null){
			return false;
		}

		//형태소 정보만 얻어옴
		Set<String> morphes = this.getMorphes(morphScoredTagsMap);

		//각 형태소와 품사 정보를 lattice에 삽입
		for (String morph : morphes) {
			int beginIdx = curIndex-morph.length()+1;
			int endIdx = curIndex+1;

			//형태소에 대한 품사 및 점수(observation) 정보를 List 형태로 가져옴
			List<ScoredTag> scoredTags = morphScoredTagsMap.get(morph);
			for (ScoredTag scoredTag : scoredTags) {
				this.insertLattice(beginIdx,endIdx,morph,scoredTag,scoredTag.getScore());
			}
		}
		return true;
	}

	private Map<String, List<IrregularNode>> getIrregularNodes(char jaso) {
		return this.resources.getIrrTrie().getTrieDictionary().get(jaso);
	}

	private void insertLattice(int beginIdx, int endIdx, String morph,
			Tag tag, double score) {
		this.lattice.put(beginIdx,endIdx,morph,tag.getTag(),tag.getTagId(),score);
	}

	private Set<String> getMorphes(
			Map<String, List<ScoredTag>> morphScoredTagMap) {
		return morphScoredTagMap.keySet();
	}

	private Map<String, List<ScoredTag>> getMorphScoredTagsMap(char jaso) {
		return this.resources.getObservation().getTrieDictionary().get(jaso);
	}
	private Map<String, List<ScoredTag>> getMorphScoredTagMapFromUserDic(char jaso){
		if(this.userDic == null){
			return null;
		}
		return this.userDic.getTrieDictionary().get(jaso);
	}

	public void load(String modelPath){
		this.resources.load(modelPath);
	}

	//기분석 사전
	public void setFWDic(String filename) {		
		try {
			CorpusParser corpusParser = new CorpusParser();
			BufferedReader br = new BufferedReader(new FileReader(filename));
			String line;
			this.fwd = new HashMap<String, List<Pair<String, String>>>();
			while ((line = br.readLine()) != null) {
				String[] tmp = line.split("\t");
				//주석이거나 format에 안 맞는 경우는 skip
				if (tmp.length != 2 || tmp[0].charAt(0) == '#'){
					tmp = null;
					continue;
				}
				ProblemAnswerPair problemAnswerPair = corpusParser.parse(line);
				List<Pair<String,String>> convertAnswerList = new ArrayList<>();
				for (Pair<String, String> pair : problemAnswerPair.getAnswerList()) {
					convertAnswerList.add(
							new Pair<String, String>(pair.getFirst(), pair.getSecond()));
				}

				this.fwd.put(problemAnswerPair.getProblem(),
						convertAnswerList);
				tmp = null;
				problemAnswerPair = null;
				convertAnswerList = null;
			}			
			br.close();

			//init
			corpusParser = null;
			br = null;
			line = null;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void setUserDic(String userDic) {
		try {
			this.userDic = new Observation();
			BufferedReader br = new BufferedReader(new FileReader(userDic));
			String line = null;
			while((line = br.readLine()) != null){
				line = line.trim();				
				if(line.length() == 0 || line.charAt(0) == '#')continue;
				int lastIdx = line.lastIndexOf("\t");

				String morph;
				String pos;
				//사용자 사전에 태그가 없는 경우에는 고유 명사로 태깅
				if(lastIdx == -1){
					morph = line.trim();
					pos = "NNP";
				}else{
					morph = line.substring(0, lastIdx);
					pos = line.substring(lastIdx+1);
				}
				this.userDic.put(morph, pos, this.resources.getTable().getId(pos), 0.0);

				line = null;
				morph = null;
				pos = null;
			}
			br.close();

			//init
			br = null;
			line = null;
			this.userDic.getTrieDictionary().buildFailLink();
		} catch (Exception e) {		
			e.printStackTrace();
		}		
	}
}
