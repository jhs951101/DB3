package hufs.ces.udp;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import hufs.ces.utils.DBConn;


public class UDPMessangerRegServer extends Thread {

	final static int BUF_SIZE = 65535;
	public final static int DEFAULT_PORT = 7770;
	
	private Connection conn = null;
	
	private int bufferSize; // in bytes
	private DatagramSocket socket;

	private SocketAddress theSender;
	private String chatID;
	private String roomID;
	
	private String fromHost;
	private int fromPort;
	
	public UDPMessangerRegServer(int port, int bufferSize) 
			throws SocketException {
		this.bufferSize = bufferSize;
		this.socket = new DatagramSocket(port);

		updateAllChatStateOff();
	}
	public UDPMessangerRegServer(int port) throws SocketException {
		this(port, BUF_SIZE);
	}
	public UDPMessangerRegServer() throws SocketException {
		this(DEFAULT_PORT); 
	}

	public void run() {	  
		byte[] buffer = new byte[bufferSize];

		while (true) {
			DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
			try {			
				socket.receive(incoming);
				theSender = incoming.getSocketAddress();
				InetAddress inetHost = incoming.getAddress();
				fromHost = inetHost.getHostAddress();
				fromPort = incoming.getPort();
				
				System.out.println("theSender="+theSender.toString()+"<<");
				System.out.println("host ip="+fromHost+":"+"port="+fromPort);

				byte[] data = new byte[incoming.getLength()];
				System.arraycopy(incoming.getData(), 0, data, 0, incoming.getLength());
				String inText = new String(data, "UTF-8");
				System.out.println("inText="+inText+"<<");
				
				if (inText.startsWith("register##")) {
					String[] regInfo = inText.split("##");
					
					chatID = regInfo[1];
					roomID = regInfo[2];  // split() 함수를 통해 chatID와 roomID를 부여받음
							
					System.out.println("id="+chatID+"<<");
					insertRegisterRecord (chatID, fromHost, fromPort, "on");
					register(chatID, roomID);
				}
				else if (inText.startsWith("##disconnect##")) {
					String id = inText.substring("##disconnect##".length());
					int updcount = updateConnRecord (id, "off");
					System.out.println(id+" set state off, retcount="+updcount);
				}
				else if (inText.startsWith("##send:")) {
					String restText = inText.substring("##send:".length());
					String[] indata = restText.split("##", 2);
					chatID = indata[0];
					String msgText = indata[1];
					System.out.println("chatID="+chatID+"--msgText="+msgText+"<<");
					String[] buddyIDs = findBuddys(chatID);  // buddyIDs[]: 'chatID'가 들어가 있는 Chat Room 내의 또 다른 사람들 목록
					
					for(int i=0; i<buddyIDs.length; i++) {
						System.out.println("chatID="+chatID + ",buddyID="+buddyIDs[i] + "--msgText="+msgText+"<<");
						sendMsg(buddyIDs[i], msgText);
					}
				}

			}
			catch (IOException e) {
				System.err.println(e);
			}
		} // end while

	}  // end run
	int  insertRegisterRecord (String chatID, String hostIP, int port, String chatState) {

		int affectedRows = 0;
		try {
			conn = new DBConn().getConnection();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		PreparedStatement pstmt = null;

		try {
			String SQL = "select * from id_address_table where chat_state = \'off\'"
					+ " and chat_id = ? ";
			ResultSet resultSet = null;
			pstmt = conn.prepareStatement(SQL, 
					ResultSet.TYPE_SCROLL_INSENSITIVE, 
					ResultSet.CONCUR_UPDATABLE);
			pstmt.setString(1, chatID);
			resultSet = pstmt.executeQuery();

			int rowcount = 0;
			if (resultSet.last()) {
				rowcount = resultSet.getRow();
				//resultSet.beforeFirst(); 
			}	  
			resultSet.close();
			pstmt.close();

			if (rowcount > 0) {
				SQL = "UPDATE id_address_table SET "
						+ "host_ip=?, port=?, chat_state=? WHERE chat_id=?";

				pstmt = conn.prepareStatement(SQL);
				pstmt.setString(1, hostIP);
				pstmt.setInt(2, port);
				pstmt.setString(3, chatState);
				pstmt.setString(4, chatID);

				affectedRows = pstmt.executeUpdate();
			}
			else {
				SQL = "SELECT * FROM id_address_table WHERE chat_id = ?";
				// 한 User가 여러 개의 방에 접속할 수 있으므로, id_address_table 테이블에 insert 하기 전 중복 검사를 실시함
				
				pstmt = conn.prepareStatement(SQL);
				pstmt.setString(1, chatID);
				
				ResultSet r = pstmt.executeQuery();
				
				if(!r.next()) {
					SQL = "INSERT INTO id_address_table(chat_id, host_ip, port, chat_state) "
							+ "VALUES(?,?,?,?)";
	
					pstmt = conn.prepareStatement(SQL);
					pstmt.setString(1, chatID);
					pstmt.setString(2, hostIP);
					pstmt.setInt(3, port);
					pstmt.setString(4, chatState);
	
					affectedRows = pstmt.executeUpdate();
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			try {
				if (pstmt!=null) pstmt.close();
				if (conn!=null) conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}

		return affectedRows;
	}
	int updateConnRecord (String chatID, String chatState) {

		int affectedRows = 0;
		try {
			conn = new DBConn().getConnection();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		PreparedStatement pstmt = null;

		String SQL = "UPDATE id_address_table SET chat_state=? WHERE chat_id=?";

		try {
			pstmt = conn.prepareStatement(SQL);
			pstmt.setString(1, chatState);
			pstmt.setString(2, chatID);

			affectedRows = pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			try {
				if (pstmt!=null) pstmt.close();
				if (conn!=null) conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}

		return affectedRows;
	}
	public void register (String chatID, String roomID) {  // 한 User가 Chat Room에 접속할 때 테이블에 삽입함

		String SQL = null;
		PreparedStatement pstmt = null;
		
		try {
			conn = new DBConn().getConnection();
			
			SQL = "INSERT INTO room_table(chat_id, room_id, conn_state) "
					+ "VALUES(?,?,?)";
			pstmt = conn.prepareStatement(SQL);
			
			pstmt.setString(1, chatID);
			pstmt.setString(2, roomID);
			pstmt.setString(3, "on");
			
			pstmt.executeUpdate();
			
			updateConnRecord (chatID, "conn");

		} catch (SQLException e) {
			e.printStackTrace();
			
		} catch(ClassNotFoundException e){
			e.printStackTrace();
			
		} finally {
			try {
				if (pstmt!=null) pstmt.close();
				if (conn!=null) conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}
	void updateAllChatStateOff() {

		int affectedRows = 0;
		try {
			conn = new DBConn().getConnection();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		PreparedStatement pstmt = null;

		String SQL = "UPDATE id_address_table SET chat_state=\'off\' ";

		try {
			pstmt = conn.prepareStatement(SQL);

			affectedRows = pstmt.executeUpdate();
			System.out.println(affectedRows+" rows are changed to off");

			// clear all record in connection table
			Statement stmt  = conn.createStatement();
			stmt.execute("TRUNCATE room_table");  // room_table reset

		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			try {
				if (pstmt!=null) pstmt.close();
				if (conn!=null) conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}

	String[] findBuddys(String chatID) {  // 한 User가 들어가 있는 Chat Room 내의 또 다른 사람들을 찾아냄
		
		String[] result = null;
		
		try {
			conn = new DBConn().getConnection();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		String SQL = null;
		PreparedStatement pstmt = null;
		ResultSet resultSet = null;
		
		try {
			SQL = "SELECT r1.chat_id FROM room_table r1 WHERE r1.chat_id != ? AND r1.room_id IN "
					+ "(SELECT r2.room_id FROM room_table r2 WHERE r2.chat_id = ?)";
			pstmt = conn.prepareStatement(SQL);
			
			pstmt.setString(1, chatID);
			pstmt.setString(2, chatID);
			
			resultSet = pstmt.executeQuery();
			
			ArrayList<String> bs = new ArrayList<String>();

			while(resultSet.next()) {
				bs.add(resultSet.getString(1));
			}
			
			result = new String[bs.size()];
			
			for(int i=0; i<result.length; i++)
				result[i] = bs.get(i);
			
			System.out.println("chatID="+chatID + "--buddy count="+bs.size());
			
		} catch (SQLException e) {
			e.printStackTrace();
			
		} finally {
			try {
				if (pstmt!=null) pstmt.close();
				if (conn!=null) conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		
		return result;

	}

	void sendMsg(String chatID, String msgText) {

		String toHost = null;
		int toPort = 0;

		try {
			conn = new DBConn().getConnection();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		String SQL = null;
		PreparedStatement pstmt = null;
		ResultSet resultSet = null;
		
		try {
			SQL = "select * from id_address_table where chat_id = ? ";
			pstmt = conn.prepareStatement(SQL, 
					ResultSet.TYPE_SCROLL_INSENSITIVE, 
					ResultSet.CONCUR_UPDATABLE);
			pstmt.setString(1, chatID);
			resultSet = pstmt.executeQuery();

			int rowcount = 0;
			if (resultSet.last()) {
				rowcount = resultSet.getRow();
				//resultSet.beforeFirst(); 
			}			
			if (rowcount > 0) { 
				resultSet.first();
				toHost = resultSet.getString("host_ip");
				toPort = resultSet.getInt("port");
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			try {
				if (pstmt!=null) pstmt.close();
				if (conn!=null) conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		
		try {
			InetAddress hostAddr = InetAddress.getByName(toHost);
			byte[] msgData = msgText.getBytes("UTF-8");
			DatagramPacket outgoing = new DatagramPacket(msgData, msgData.length, hostAddr, toPort);
			
			System.out.println("inetaddr="+hostAddr+",outaddr="+outgoing.getAddress()
			+",port="+toPort+"--Msg="+msgText+"<<");
			socket.send(outgoing);
		}
		catch (java.io.UnsupportedEncodingException ex) {
			System.err.println(ex);
		}
		catch (IOException ex) {
			System.err.println(ex);
		}

	}
	public static void main(String[] args) {

		try {
			UDPMessangerRegServer server = new UDPMessangerRegServer();
			server.start();
		}
		catch (SocketException ex) {
			System.err.println(ex);
		}

	}

}
