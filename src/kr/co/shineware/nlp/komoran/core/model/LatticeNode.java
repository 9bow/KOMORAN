package kr.co.shineware.nlp.komoran.core.model;

import kr.co.shineware.nlp.komoran.model.MorphTag;

public class LatticeNode {
	private int beginIdx;
	private int endIdx;
	private MorphTag morphTag;
	private double score;
	
	public LatticeNode(){
		;
	}
	
	public LatticeNode(int beginIdx, int endIdx, MorphTag morphTag, double score) {
		super();
		this.beginIdx = beginIdx;
		this.endIdx = endIdx;
		this.morphTag = morphTag;
		this.score = score;
	}
	public int getBeginIdx() {
		return beginIdx;
	}
	public void setBeginIdx(int beginIdx) {
		this.beginIdx = beginIdx;
	}
	public int getEndIdx() {
		return endIdx;
	}
	public void setEndIdx(int endIdx) {
		this.endIdx = endIdx;
	}
	public MorphTag getMorphTag() {
		return morphTag;
	}
	public void setMorphTag(MorphTag morphTag) {
		this.morphTag = morphTag;
	}
	public double getScore() {
		return score;
	}
	public void setScore(double score) {
		this.score = score;
	}
}
