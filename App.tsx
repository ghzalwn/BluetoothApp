import React, {useEffect, useState} from 'react';
import {
  NativeEventEmitter,
  NativeModules,
  View,
  Button,
  FlatList,
  Text,
  Alert,
} from 'react-native';
import {Peripheral} from './types';

const {BLEModule} = NativeModules;
const BLEEventEmitter = new NativeEventEmitter(BLEModule);

const App: React.FC = () => {
  const [peripherals, setPeripherals] = useState<Peripheral[]>([]);
  const [isScan, setIsScan] = useState<boolean>(false);

  useEffect(() => {
    const onPeripheralDiscovered = BLEEventEmitter.addListener(
      'onPeripheralDiscovered',
      (peripheral: Peripheral) => {
        console.log(peripheral);
        setPeripherals(prev => {
          if (prev.some(p => p.id === peripheral.id)) {
            return prev;
          } // Avoid duplicates
          return [...prev, peripheral];
        });
      },
    );

    const onScanStart = BLEEventEmitter.addListener('onScanStart', () => {
      Alert.alert('Scan Started', 'BLE scanning has started.');
      setIsScan(true);
    });

    const onScanStop = BLEEventEmitter.addListener('onScanStop', () => {
      Alert.alert('Scan Stopped', 'BLE scanning has stopped.');
      setIsScan(false);
    });

    return () => {
      onPeripheralDiscovered.remove();
      onScanStart.remove();
      onScanStop.remove();
    };
  }, []);

  const startScan = async () => {
    console.log('test', BLEModule);
    try {
      await BLEModule.startScan();
    } catch (error) {
      console.log(error);
      // Alert.alert('Error', error.message || 'Failed to start scan');
    }
  };

  const stopScan = async () => {
    try {
      await BLEModule.stopScan();
    } catch (error) {
      // Alert.alert('Error', error.message || 'Failed to stop scan');
    }
  };

  const renderPeripheral = ({item}: {item: Peripheral}) => (
    <Text>
      {item.name || 'Unknown Device'} - RSSI: {item.rssi}
    </Text>
  );

  return (
    <View style={{flex: 1, padding: 20, backgroundColor: 'white'}}>
      {isScan ? (
        <Button title="Stop Scan" onPress={stopScan} />
      ) : (
        <Button title="Start Scan" onPress={startScan} />
      )}

      <FlatList
        data={peripherals}
        keyExtractor={item => item.id}
        renderItem={renderPeripheral}
      />
    </View>
  );
};

export default App;
