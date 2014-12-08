/*
 * Some portion .. Copyright (c) 2014 CIYAM Developers

 Distributed under the MIT/X11 software license, please refer to the file license.txt
 in the root project directory or http://www.opensource.org/licenses/mit-license.php.

 */

package nxt;


import nxt.at.AT_API_Helper;
import nxt.at.AT_Constants;
import nxt.at.AT_Controller;
import nxt.at.AT_Exception;
import nxt.at.AT_Machine_State;
import nxt.at.AT_Transaction;
import nxt.db.Db;
import nxt.db.DbKey;
import nxt.db.DbUtils;
import nxt.db.VersionedEntityDbTable;
import nxt.util.Convert;
import nxt.util.Listener;
import nxt.Account;
import nxt.TransactionImpl.BuilderImpl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class AT extends AT_Machine_State {

	static {
		Nxt.getBlockchainProcessor().addListener(new Listener<Block>() {
			@Override
			public void notify(Block block) {
				for(Long id : pendingFees.keySet()) {
					Account atAccount = Account.getAccount(id);
					atAccount.addToBalanceAndUnconfirmedBalanceNQT(-pendingFees.get(id));
				}
				List<TransactionImpl> transactions = new ArrayList<>();
				for(AT_Transaction atTransaction : pendingTransactions) {
					Account.getAccount(AT_API_Helper.getLong(atTransaction.getSenderId())).addToBalanceAndUnconfirmedBalanceNQT(-atTransaction.getAmount());
					Account.getAccount(AT_API_Helper.getLong(atTransaction.getRecipientId())).addToBalanceAndUnconfirmedBalanceNQT(atTransaction.getAmount());
					
					Attachment.AbstractAttachment attachment = new Attachment.AutomatedTransactionsPayment();
					TransactionImpl.BuilderImpl builder = new TransactionImpl.BuilderImpl((byte)1, Genesis.CREATOR_PUBLIC_KEY,
							atTransaction.getAmount(), 0L, block.getTimestamp(), (short)1440, attachment);
					
					builder.senderId(AT_API_Helper.getLong(atTransaction.getSenderId()))
						.recipientId(AT_API_Helper.getLong(atTransaction.getRecipientId()))
						.blockId(block.getId())
						.height(block.getHeight())
						.blockTimestamp(block.getTimestamp())
						.ecBlockHeight(0)
						.ecBlockId(0L);
					
					try {
						TransactionImpl transaction = builder.build();
						if(!TransactionDb.hasTransaction(transaction.getId())) {
							transactions.add(transaction);
						}
					}
					catch(NxtException.NotValidException e) {
						throw new RuntimeException("Failed to construct AT payment transaction", e);
					}
				}
				
				if(transactions.size() > 0) {
					try (Connection con = Db.getConnection()) {
						TransactionDb.saveTransactions(con, transactions);
					}
					catch(SQLException e) {
						throw new RuntimeException(e.toString(), e);
					}
				}

			}

		}, BlockchainProcessor.Event.AFTER_BLOCK_APPLY);
	}
	
	private static final LinkedHashMap<Long, Long> pendingFees = new LinkedHashMap<>();
	private static final List<AT_Transaction> pendingTransactions = new ArrayList<>();
	
	public static void clearPendingFees() {
		pendingFees.clear();
	}
	
	public static void clearPendingTransactions() {
		pendingTransactions.clear();
	}
	
	public static void addPendingFee(long id, long fee) {
		pendingFees.put(id, fee);
	}
	
	public static void addPendingFee(byte[] id, long fee) {
		addPendingFee(AT_API_Helper.getLong(id), fee);
	}
	
	public static void addPendingTransaction(AT_Transaction atTransaction) {
		pendingTransactions.add(atTransaction);
	}

	public static class ATState {

		private final long atId;
		private final DbKey dbKey;
		private byte[] state;
		private int prevHeight;
		private int nextHeight;
		private int sleepBetween;
		private long prevBalance;
		private boolean freezeWhenSameBalance;

		private ATState(long atId, byte[] state , int prevHeight , int nextHeight, int sleepBetween, long prevBalance, boolean freezeWhenSameBalance) {
			this.atId = atId;
			this.dbKey = atStateDbKeyFactory.newKey(this.atId);
			this.state = state;
			this.nextHeight = nextHeight;
			this.sleepBetween = sleepBetween;
			this.prevBalance = prevBalance;
			this.freezeWhenSameBalance = freezeWhenSameBalance;
		}

		private ATState(ResultSet rs) throws SQLException {
			this.atId = rs.getLong("at_id");
			this.dbKey = atStateDbKeyFactory.newKey(this.atId);
			this.state = rs.getBytes("state");
			this.prevHeight = rs.getInt("prev_height");
			this.nextHeight = rs.getInt("next_height");
			this.sleepBetween = rs.getInt("sleep_between");
			this.prevBalance = rs.getLong("prev_balance");
			this.freezeWhenSameBalance = rs.getBoolean("freeze_when_same_balance");
		}

		private void save(Connection con) throws SQLException {
			try (PreparedStatement pstmt = con.prepareStatement("MERGE INTO at_state (at_id, "
					+ "state, prev_height ,next_height, sleep_between, prev_balance, freeze_when_same_balance, height, latest) "
					+ "KEY (at_id, height) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)")) {
				int i = 0;
				pstmt.setLong(++i, atId);
				DbUtils.setBytes(pstmt, ++i, state);
				pstmt.setInt( ++i , prevHeight);
				pstmt.setInt(++i, nextHeight);
				pstmt.setInt(++i, sleepBetween);
				pstmt.setLong(++i, prevBalance);
				pstmt.setBoolean(++i, freezeWhenSameBalance);
				pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
				pstmt.executeUpdate();
			}
		}

		public long getATId() {
			return atId;
		}

		public byte[] getState() {
			return state;
		}
		
		public int getPrevHeight() {
			return prevHeight;
		}

		public int getNextHeight() {
			return nextHeight;
		}
		
		public int getSleepBetween() {
			return sleepBetween;
		}
		
		public long getPrevBalance() {
			return prevBalance;
		}
		
		public boolean getFreezeWhenSameBalance() {
			return freezeWhenSameBalance;
		}

		public void setState(byte[] newState) {
			state = newState;
		}
		
		public void setPrevHeight(int prevHeight){
			this.prevHeight = prevHeight; 
		}
		
		public void setNextHeight(int newNextHeight) {
			nextHeight = newNextHeight;
		}
		
		public void setSleepBetween(int newSleepBetween) {
			this.sleepBetween = newSleepBetween;
		}
		
		public void setPrevBalance(long newPrevBalance) {
			this.prevBalance = newPrevBalance;
		}
		
		public void setFreezeWhenSameBalance(boolean newFreezeWhenSameBalance) {
			this.freezeWhenSameBalance = newFreezeWhenSameBalance;
		}
	}

	private static final DbKey.LongKeyFactory<AT> atDbKeyFactory = new DbKey.LongKeyFactory<AT>("id") {
		@Override
		public DbKey newKey(AT at) {
			return at.dbKey;
		}
	};
	
	private static final VersionedEntityDbTable<AT> atTable = new VersionedEntityDbTable<AT>("at", atDbKeyFactory) {
		@Override
		protected AT load(Connection con, ResultSet rs) throws SQLException {
			//return new AT(rs);
			throw new RuntimeException("AT attempted to be created with atTable.load");
		}
		@Override
		protected void save(Connection con, AT at) throws SQLException {
			at.save(con);
		}
		@Override
		protected String defaultSort() {
			return " ORDER BY id ";
		}
	};

	private static final DbKey.LongKeyFactory<ATState> atStateDbKeyFactory = new DbKey.LongKeyFactory<AT.ATState>("at_id") {
		@Override
		public DbKey newKey(ATState atState) {
			return atState.dbKey;
		}
	};

	private static final VersionedEntityDbTable<ATState> atStateTable = new VersionedEntityDbTable<ATState>("at_state", atStateDbKeyFactory) {
		@Override
		protected ATState load(Connection con, ResultSet rs) throws SQLException {
			return new ATState(rs);
		}
		@Override
		protected void save(Connection con, ATState atState) throws SQLException {
			atState.save(con);
		}
		@Override
		protected String defaultSort() {
			return " ORDER BY prev_height, height, at_id ";
		}
	};

	
	public static Collection<Long> getAllATIds() 
	{
		try ( PreparedStatement pstmt = Db.getConnection().prepareStatement( "SELECT id FROM at WHERE latest = TRUE" ) )
		{
			ResultSet result = pstmt.executeQuery();
			List<Long> ids = new ArrayList<>();
			while(result.next()) {
				ids.add(result.getLong("id"));
			}
			return ids;
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}

	public static AT getAT(byte[] id) 
	{
		return getAT( AT_API_Helper.getLong( id ) );
	}

	public static AT getAT(Long id) {
		try (PreparedStatement pstmt = Db.getConnection().prepareStatement("SELECT at.id, at.creator_id, at.name, at.description, at.version, "
				+ "at_state.state, at.csize, at.dsize, at.c_user_stack_bytes, at.c_call_stack_bytes, "
				+ "at.minimum_fee, at.creation_height, at_state.sleep_between, at_state.freeze_when_same_balance, "
				+ "at.ap_code "
				+ "FROM at INNER JOIN at_state ON at.id = at_state.at_id "
				+ "WHERE at.latest = TRUE AND at_state.latest = TRUE "
				+ "AND at.id = ?"))
		{
			int i = 0;
			pstmt.setLong( ++i ,  id );
			ResultSet result = pstmt.executeQuery();
			List<AT> ats = createATs( result );
			if(ats.size() > 0) {
				return ats.get(0);
			}
			return null;
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}

	public static List<AT> getATsIssuedBy(Long accountId) {
		try (PreparedStatement pstmt = Db.getConnection().prepareStatement("SELECT at.id, at.creator_id, at.name, at.description, at.version, "
				+ "at_state.state, at.csize, at.dsize, at.c_user_stack_bytes, at.c_call_stack_bytes, "
				+ "at.minimum_fee, at.creation_height, at_state.sleep_between, at_state.freeze_when_same_balance, "
				+ "at.ap_code "
				+ "FROM at INNER JOIN at_state ON at.id = at_state.at_id "
				+ "WHERE at.latest = TRUE AND at_state.latest = TRUE "
				+ "AND at.creator_id = ?"))
		{
			pstmt.setLong(1, accountId);
			ResultSet result = pstmt.executeQuery();
			return createATs( result );
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}

	static void addAT(Long atId, Long senderAccountId, String name, String description, byte[] creationBytes , int height) {

		ByteBuffer bf = ByteBuffer.allocate( 8 + 8 );
		bf.order( ByteOrder.LITTLE_ENDIAN );

		bf.putLong( atId );

		byte[] id = new byte[ 8 ];

		bf.putLong( 8 , senderAccountId );

		byte[] creator = new byte[ 8 ];
		bf.clear();
		bf.get( id , 0 , 8 );
		bf.get( creator , 0 , 8);

		AT at = new AT( id , creator , name , description , creationBytes , height );

		AT_Controller.resetMachine(at);

		atTable.insert(at);
		
		at.saveState();

		Account account = Account.addOrGetAccount(atId);
		account.apply(new byte[32], height);
	}

	public void saveState() {
		ATState state = atStateTable.get(atStateDbKeyFactory.newKey( AT_API_Helper.getLong( this.getId() ) ) );
		int prevHeight = Nxt.getBlockchain().getHeight();
		int nextHeight = prevHeight + getWaitForNumberOfBlocks();
		if(state != null) {
			state.setState(getState());
			state.setPrevHeight( prevHeight );
			state.setNextHeight(nextHeight);
			state.setSleepBetween(getSleepBetween());
			state.setPrevBalance(getP_balance());
			state.setFreezeWhenSameBalance(freezeOnSameBalance());
		}
		else {
			state = new ATState( AT_API_Helper.getLong( this.getId() ) , getState(), prevHeight, nextHeight, getSleepBetween(), getP_balance(), freezeOnSameBalance());
		}
		atStateTable.insert(state);
	}

	private static List<AT> createATs( ResultSet rs ) throws SQLException
	{
		List<AT> ats = new ArrayList<AT>();
		while ( rs.next() )
		{
			int i = 0;
			Long atId = rs.getLong( ++i );
			Long creator = rs.getLong( ++i );
			String name = rs.getString( ++i );
			String description = rs.getString( ++i );
			short version = rs.getShort( ++i );
			byte[] stateBytes = rs.getBytes( ++i );
			int csize = rs.getInt( ++i );
			int dsize = rs.getInt( ++i );
			int c_user_stack_bytes = rs.getInt( ++i );
			int c_call_stack_bytes = rs.getInt( ++i );
			long minimumFee = rs.getLong( ++i );
			int creationBlockHeight = rs.getInt( ++i );
			int sleepBetween = rs.getInt( ++i );
			boolean freezeWhenSameBalance = rs.getBoolean( ++i );
			byte[] ap_code = rs.getBytes( ++i );

			AT at = new AT( AT_API_Helper.getByteArray( atId ) , AT_API_Helper.getByteArray( creator ) , name , description , version ,
					stateBytes , csize , dsize , c_user_stack_bytes , c_call_stack_bytes , minimumFee , creationBlockHeight , sleepBetween , 
					freezeWhenSameBalance , ap_code );
			ats.add( at );

		}
		return ats;
	}

	private void save(Connection con)
	{
		try ( PreparedStatement pstmt = con.prepareStatement( "INSERT INTO at " 
				+ "(id , creator_id , name , description , version , "
				+ "csize , dsize , c_user_stack_bytes , c_call_stack_bytes , "
				+ "minimum_fee , creation_height , "
				+ "ap_code , height) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)" ) )
				{
			int i = 0;
			pstmt.setLong( ++i , AT_API_Helper.getLong( this.getId() ) );
			pstmt.setLong( ++i, AT_API_Helper.getLong( this.getCreator() ) );
			DbUtils.setString( pstmt , ++i , this.getName() );
			DbUtils.setString( pstmt , ++i , this.getDescription() );
			pstmt.setShort( ++i , this.getVersion() );
			pstmt.setInt( ++i , this.getCsize() );
			pstmt.setInt( ++i , this.getDsize() );
			pstmt.setInt( ++i , this.getC_user_stack_bytes() );
			pstmt.setInt( ++i , this.getC_call_stack_bytes() );
			pstmt.setLong( ++i , this.getMinimumFee() );
			pstmt.setInt( ++i, this.getCreationBlockHeight() );
			DbUtils.setBytes( pstmt , ++i , this.getApCode() );
			pstmt.setInt( ++i , Nxt.getBlockchain().getHeight() );

			pstmt.executeUpdate();
				}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}

	}

	private static void deleteAT( AT at )
	{
		ATState atState = atStateTable.get(atStateDbKeyFactory.newKey(AT_API_Helper.getLong(at.getId())));
		if(atState != null) {
			atStateTable.delete(atState);
		}
		atTable.delete(at);
		//TODO: release account
	}

	private static void deleteAT( Long id )
	{
		AT at = AT.getAT(id);
		if(at != null) {
			deleteAT(at);
		}
		
	}

	public static List< Long > getOrderedATs(){
		List< Long > orderedATs = new ArrayList<>();
		try (PreparedStatement pstmt = Db.getConnection().prepareStatement("SELECT at.id FROM at "
				+ "INNER JOIN at_state ON at.id = at_state.at_id INNER JOIN account ON at.id = account.id "
				+ "WHERE at.latest = TRUE AND at_state.latest = TRUE AND account.latest = TRUE "
				+ "AND at_state.next_height <= ? AND account.balance >= ? "
				+ "AND (at_state.freeze_when_same_balance = FALSE OR account.balance > at_state.prev_balance) "
				+ "ORDER BY at_state.prev_height, at_state.next_height, at.id"))
		{
			pstmt.setInt( 1 ,  Nxt.getBlockchain().getHeight() );
			pstmt.setLong(2, AT_Constants.getInstance().STEP_FEE(Nxt.getBlockchain().getHeight()));
			ResultSet result = pstmt.executeQuery();
			while ( result.next() )
			{
				Long id = result.getLong( 1 );
				orderedATs.add( id );
			}
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
		return orderedATs;
	}


	static boolean isATAccountId(Long id) {
		try ( PreparedStatement pstmt = Db.getConnection().prepareStatement( "SELECT id FROM at WHERE id = ? AND latest = TRUE" ) )
		{
			pstmt.setLong(1, id);
			ResultSet result = pstmt.executeQuery();
			return result.next();
		}
		catch (SQLException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}
	
	static void init() {}

	private final String name;    
	private final String description;
	private final DbKey dbKey;


	private AT( byte[] atId , byte[] creator , String name , String description , byte[] creationBytes , int height ) {
		super( atId , creator , creationBytes , height );
		this.name = name;
		this.description = description;
		dbKey = atDbKeyFactory.newKey(AT_API_Helper.getLong(atId));
	}

	public AT ( byte[] atId , byte[] creator , String name , String description , short version ,
			byte[] stateBytes, int csize , int dsize , int c_user_stack_bytes , int c_call_stack_bytes ,
			long minimumFee , int creationBlockHeight, int sleepBetween , 
			boolean freezeWhenSameBalance, byte[] apCode )
	{
		super( 	atId , creator , version ,
				stateBytes , csize , dsize , c_user_stack_bytes , c_call_stack_bytes ,
				minimumFee , creationBlockHeight , sleepBetween , 
				freezeWhenSameBalance , apCode );
		this.name = name;
		this.description = description;
		dbKey = atDbKeyFactory.newKey(AT_API_Helper.getLong(atId));
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public byte[] getApCode() {
		return getAp_code().array();
	}

	public byte[] getApData() {
		return getAp_data().array();
	}

}